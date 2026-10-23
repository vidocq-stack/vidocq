/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.bodoni.core.tags.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.time.Duration;
import java.util.Map;

import io.vidocq.bodoni.core.internal.XPathEngine;
import io.vidocq.bodoni.core.testing.AttributeAddingFilter;
import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServer;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** {@code <x:parse>} (Tags 3.0 section 11.2). */
class ParseTagTest {

    private static final String DOC = "<a><b>1</b></a>";
    private static final String FILTERED = "<test attr='value'>xmltext</test>";
    private static final String SEEN = "$d/test[@seen='yes']";
    private static final String RESOLVE = "<external><resolved>Entity Resolved</resolved></external>";
    private static final String NULL_OR_EMPTY = "x:parse: the XML document is null or empty";

    /** The body of the TCK {@code positiveParseSystemIdTest}, as a container hands it over. */
    private static final String SYSTEM_ID_BODY = """

                    <?xml version="1.0"?>
                    <!DOCTYPE root [
                        <!ENTITY external SYSTEM "xfiles/resolve.xml">
                    ]>
                    <root>
                        &external;
                    </root>
                """;

    private final TestServletContext context = new TestServletContext();
    private final TestPageContext pc =
            new TestPageContext(new TestHttpServletRequest(context), new TestHttpServletResponse(), context);
    private TestHttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private static ParseTag parse(Object doc) {
        ParseTag tag = new ParseTag();
        tag.setDoc(doc);
        tag.setVar("d");
        return tag;
    }

    private static ParseTag parseBody() {
        ParseTag tag = new ParseTag();
        tag.setVar("d");
        return tag;
    }

    private static Body text(String content) {
        return p -> p.getOut().write(content);
    }

    private void run(ParseTag tag, Body body) throws Exception {
        TagHarness.run(tag, pc, body);
    }

    private void run(ParseTag tag) throws Exception {
        run(tag, Body.NONE);
    }

    private String xpath(String select) throws Exception {
        return XPathEngine.string(pc, null, select);
    }

    // ---- var, varDom and their scopes ---------------------------------------

    @Test
    void varAndVarDomExportTheParsedDocument() throws Exception {
        ParseTag tag = parse(DOC);
        tag.setVarDom("dd");
        tag.setScopeDom("session");

        run(tag);

        Document doc = assertInstanceOf(Document.class, pc.getAttribute("d", PageContext.PAGE_SCOPE));
        assertEquals("1", doc.getDocumentElement().getTextContent());
        assertSame(doc, pc.getAttribute("dd", PageContext.SESSION_SCOPE));
        assertEquals("1", xpath("$d/a/b"));
    }

    @Test
    void scopeAppliesToVar() throws Exception {
        ParseTag tag = parse(DOC);
        tag.setScope("request");

        run(tag);

        assertInstanceOf(Document.class, pc.getAttribute("d", PageContext.REQUEST_SCOPE));
        assertNull(pc.getAttribute("d", PageContext.PAGE_SCOPE));
    }

    @Test
    void aReaderIsParsed() throws Exception {
        run(parse(new StringReader(DOC)));

        assertEquals("1", xpath("$d/a/b"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void theXmlAttributeIsADeprecatedAliasOfDoc() throws Exception {
        ParseTag tag = parseBody();
        tag.setXml(DOC);

        run(tag);

        assertEquals("1", xpath("$d/a/b"));
    }

    @Test
    void theBodyIsTrimmedThenParsed() throws Exception {
        run(parseBody(), text("\n  <?xml version=\"1.0\"?>\n<a/>  "));

        assertEquals(1.0, XPathEngine.select(pc, null, "count($d/a)"));
    }

    // ---- null and error handling --------------------------------------------

    @Test
    void aNullDocIsAJspException() {
        ParseTag tag = parse(null);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY, e.getMessage());
    }

    @Test
    void anEmptyDocIsAJspException() {
        ParseTag tag = parse("");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY, e.getMessage());
    }

    @Test
    void anEmptyBodyIsAJspException() {
        ParseTag tag = parseBody();

        JspException e = assertThrows(JspException.class, () -> run(tag, text("  \n ")));

        assertEquals(NULL_OR_EMPTY, e.getMessage());
    }

    @Test
    void noBodyAtAllIsAJspException() {
        ParseTag tag = parseBody();

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY, e.getMessage());
    }

    @Test
    void aDocOfAnotherTypeIsATagException() {
        ParseTag tag = parse(42);

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().contains("java.lang.Integer"), e.getMessage());
    }

    @Test
    void aMalformedDocumentIsAJspExceptionKeepingTheParseFailure() {
        ParseTag tag = parse("<a>");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertTrue(e.getMessage().startsWith("x:parse: "), e.getMessage());
        assertInstanceOf(SAXException.class, e.getCause());
    }

    @ParameterizedTest(name = "scope {0}")
    @ValueSource(strings = {"scope", "scopeDom"})
    void anInvalidScopeIsATagException(String attribute) {
        ParseTag tag = parse(DOC);
        if (attribute.equals("scope")) {
            tag.setScope("galaxy");
        } else {
            tag.setVarDom("dd");
            tag.setScopeDom("galaxy");
        }

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().contains("galaxy"), e.getMessage());
    }

    @Test
    void anInvalidSystemIdIsATagExceptionNamingIt() {
        ParseTag tag = parse(DOC);
        tag.setSystemId("/../outside.xml");

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertEquals("x:parse: the system id '/../outside.xml' leaves the context root", e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    // ---- no DTD validation --------------------------------------------------

    @Test
    void aDoctypeWithAnInternalSubsetIsNotValidated() throws Exception {
        run(parseBody(), text("""

                    <?xml version="1.0" encoding="UTF-8" ?>
                    <!DOCTYPE test [
                        <!ELEMENT test (docs)>
                        <!ELEMENT docs EMPTY>
                    ]>
                    <test>
                        <docs>DATA</docs>
                    </test>
                """));

        assertEquals("DATA", xpath("$d//docs"));
    }

    @Test
    void anUndeclaredElementStillParses() throws Exception {
        run(parse("<!DOCTYPE test [<!ELEMENT test EMPTY>]><test><undeclared>x</undeclared></test>"));

        assertEquals("x", xpath("$d/test/undeclared"));
    }

    // ---- filter -------------------------------------------------------------

    @Test
    void aFilterAppliesToAString() throws Exception {
        ParseTag tag = parse(FILTERED);
        tag.setFilter(new AttributeAddingFilter());

        run(tag);

        assertEquals("xmltext", xpath(SEEN));
    }

    @Test
    void aFilterAppliesToAReader() throws Exception {
        ParseTag tag = parse(new StringReader(FILTERED));
        tag.setFilter(new AttributeAddingFilter());

        run(tag);

        assertEquals("xmltext", xpath(SEEN));
    }

    @Test
    void aFilterAppliesToTheBody() throws Exception {
        ParseTag tag = parseBody();
        tag.setFilter(new AttributeAddingFilter());

        run(tag, text("\n    " + FILTERED + "\n"));

        assertEquals("xmltext", xpath(SEEN));
    }

    @Test
    void aNullFilterDoesNotFilter() throws Exception {
        ParseTag tag = parse(FILTERED);
        tag.setFilter(null);

        run(tag);

        assertEquals("xmltext", xpath("$d/test"));
        assertEquals(0.0, XPathEngine.select(pc, null, "count($d//@seen)"));
    }

    // ---- system id ----------------------------------------------------------

    @Test
    void aRelativeSystemIdResolvesEntitiesInTheWebApplication() throws Exception {
        context.resource("/x/xfiles/resolve.xml", RESOLVE);
        ParseTag tag = parseBody();
        tag.setSystemId("/x/doc.xml");

        run(tag, text(SYSTEM_ID_BODY));

        assertEquals("Entity Resolved", xpath("$d//resolved"));
    }

    @Test
    void anExternalDtdSubsetIsReadThroughTheSystemId() throws Exception {
        context.resource("/x/r.dtd", "<!ENTITY e \"from dtd\">");
        ParseTag tag = parse("<!DOCTYPE r SYSTEM \"r.dtd\"><r>&e;</r>");
        tag.setSystemId("/x/doc.xml");

        run(tag);

        assertEquals("from dtd", xpath("$d/r"));
    }

    @Test
    void anAbsoluteSystemIdResolvesEntitiesOverHttp() throws Exception {
        server = TestHttpServer.start().route("/x/xfiles/resolve.xml", 200, Map.of(), RESOLVE);
        ParseTag tag = parse(SYSTEM_ID_BODY.strip());
        tag.setSystemId("http://127.0.0.1:" + server.port() + "/x/doc.xml");

        run(tag);

        assertEquals("Entity Resolved", xpath("$d//resolved"));
    }

    @Test
    void withoutASystemIdAnExternalEntityIsRefused() {
        ParseTag tag = parse(SYSTEM_ID_BODY.strip());

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals("x:parse: external reference 'xfiles/resolve.xml' refused, system id '(none)': no system id",
                e.getMessage());
        assertTrue(chainHas(e, SAXException.class), "the parse failure is kept");
    }

    @Test
    void aCrossOriginEntityIsRefusedWithoutAConnection() {
        server = TestHttpServer.start();
        String target = "http://localhost:" + server.port() + "/e.xml";
        ParseTag tag = parse("<!DOCTYPE r [<!ENTITY e SYSTEM '" + target + "'>]><r>&e;</r>");
        tag.setSystemId("http://127.0.0.1:" + server.port() + "/x/doc.xml");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertTrue(e.getMessage().contains(target), e.getMessage());
        assertTrue(e.getMessage().endsWith("not the same origin"), e.getMessage());
        assertTrue(server.requestedPaths().isEmpty());
    }

    @Test
    void underARelativeSystemIdAnEntityOutsideTheWebApplicationIsRefused() {
        ParseTag tag = parse("<!DOCTYPE r [<!ENTITY e SYSTEM 'file:///etc/hosts'>]><r>&e;</r>");
        tag.setSystemId("/x/doc.xml");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals("x:parse: external reference 'file:///etc/hosts' refused, system id '/x/doc.xml': "
                + "outside the web application", e.getMessage());
    }

    @Test
    void anEntityExpansionBombFailsFast() {
        StringBuilder dtd = new StringBuilder("<!DOCTYPE lolz [<!ENTITY lol0 \"lol\">");
        for (int i = 1; i <= 9; i++) {
            dtd.append("<!ENTITY lol").append(i).append(" \"").append(("&lol" + (i - 1) + ";").repeat(10)).append("\">");
        }
        ParseTag tag = parse(dtd.append("]><lolz>&lol9;</lolz>").toString());

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(JspException.class, () -> run(tag)));
    }

    // ---- reuse --------------------------------------------------------------

    @Test
    void aReusedInstanceKeepsItsAttributes() throws Exception {
        ParseTag tag = parse(DOC);

        run(tag);
        Document first = (Document) pc.getAttribute("d");
        run(tag);
        Document second = (Document) pc.getAttribute("d");

        assertNotSame(first, second);
        assertTrue(first.isEqualNode(second));
    }

    @Test
    void aReusedInstanceParsesItsNewBody() throws Exception {
        ParseTag tag = parseBody();

        run(tag, text("<first/>"));
        run(tag, text("<second/>"));

        assertEquals("second", ((Document) pc.getAttribute("d")).getDocumentElement().getTagName());
    }

    @Test
    void releaseClearsTheAttributes() {
        ParseTag tag = parse(DOC);
        tag.release();

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY, e.getMessage());
    }

    private static boolean chainHas(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
