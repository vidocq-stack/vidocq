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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

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
import jakarta.servlet.jsp.tagext.Tag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.w3c.dom.Document;

/** {@code <x:transform>} (Tags 3.0 section 13.2). */
class TransformTagTest {

    private static final String XSL_NS = "xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='1.0'";

    /** Wraps every text node in an {@code h4} element, HTML output. */
    private static final String H4 = "<xsl:stylesheet " + XSL_NS + "><xsl:output method='html'/>"
            + "<xsl:template match='text()'><h4><xsl:value-of select='.'/></h4></xsl:template></xsl:stylesheet>";

    /** Prints its two parameters and the document text, text output. */
    private static final String PARAMS = "<xsl:stylesheet " + XSL_NS + "><xsl:output method='text'/>"
            + "<xsl:param name='first'>default-first</xsl:param><xsl:param name='second'>default-second</xsl:param>"
            + "<xsl:template match='/'>[<xsl:value-of select='$first'/>|<xsl:value-of select='$second'/>|"
            + "<xsl:value-of select='.'/>]</xsl:template></xsl:stylesheet>";

    private static final String DOC = "<a><b>Arbitrary Text</b></a>";
    private static final String TRANSFORMED = "<h4>Arbitrary Text</h4>";
    private static final String NULL_OR_EMPTY_DOC = "x:transform: the XML document is null or empty";
    private static final String NULL_OR_EMPTY_XSLT = "x:transform: the XSLT stylesheet is null or empty";

    /** A document whose text comes from an external entity, relative to its system id. */
    private static final String ENTITY_DOC = """
            <?xml version="1.0"?>
            <!DOCTYPE root [
                <!ENTITY external SYSTEM "xfiles/resolve.xml">
            ]>
            <root>&external;</root>""";

    private static final String RESOLVE_XML = "<external><resolved>Entity Resolved</resolved></external>";
    private static final String RESOLVED = "<h4>Entity Resolved</h4>";

    /** Imports {@code xfiles/resolve.xsl}, relative to its system id, and calls its template. */
    private static final String IMPORTING = "<xsl:stylesheet " + XSL_NS + "><xsl:import href='xfiles/resolve.xsl'/>"
            + "<xsl:output method='text'/><xsl:template match='/'><xsl:call-template name='imported'/></xsl:template>"
            + "</xsl:stylesheet>";

    private static final String IMPORTED = "<xsl:stylesheet " + XSL_NS + ">"
            + "<xsl:template name='imported'>imported</xsl:template></xsl:stylesheet>";

    /** A stylesheet whose template text is the external entity {@code ent.xml}. */
    private static final String ENTITY_XSL = "<!DOCTYPE xsl:stylesheet [<!ENTITY e SYSTEM 'ent.xml'>]>"
            + "<xsl:stylesheet " + XSL_NS + "><xsl:output method='text'/><xsl:template match='/'>&e;</xsl:template>"
            + "</xsl:stylesheet>";

    /** A document whose text is an absolute {@code file:} entity. */
    private static final String FILE_ENTITY_DOC = "<!DOCTYPE r [<!ENTITY e SYSTEM 'file:///etc/hosts'>]><r>&e;</r>";

    private static final String FILE_ENTITY_REFUSED = "x:transform: external reference 'file:///etc/hosts' refused, "
            + "system id '/t/s.xsl': outside the web application";

    private static final String REFUSED_IMPORT =
            "x:transform: external reference 'xfiles/resolve.xsl' refused, system id '(none)': no system id";

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

    private static TransformTag transform(Object doc, Object xslt) {
        TransformTag tag = new TransformTag();
        tag.setDoc(doc);
        tag.setXslt(xslt);
        return tag;
    }

    private static TransformTag transformBody(Object xslt) {
        TransformTag tag = new TransformTag();
        tag.setXslt(xslt);
        return tag;
    }

    private String run(TransformTag tag) throws Exception {
        return run(tag, Body.NONE);
    }

    private String run(TransformTag tag, Body body) throws Exception {
        return TagHarness.run(tag, pc, body);
    }

    /** Runs an {@code <x:param name value/>} nested in {@code parent}. */
    private void param(Tag parent, String name, Object value) throws Exception {
        ParamTag tag = new ParamTag();
        tag.setName(name);
        tag.setValue(value);
        TagHarness.run(tag, pc, parent, Body.NONE);
    }

    // ---- inputs -------------------------------------------------------------

    @Test
    void stringsAreTransformedToThePage() throws Exception {
        assertEquals(TRANSFORMED, html(run(transform(DOC, H4))));
    }

    @Test
    void readersAreTransformed() throws Exception {
        assertEquals(TRANSFORMED, html(run(transform(new StringReader(DOC), new StringReader(H4)))));
    }

    @Test
    void sourcesAreTransformed() throws Exception {
        TransformTag tag = transform(new StreamSource(new StringReader(DOC)), new StreamSource(new StringReader(H4)));

        assertEquals(TRANSFORMED, html(run(tag)));
    }

    @Test
    void theDocumentOfXParseIsTransformed() throws Exception {
        ParseTag parse = new ParseTag();
        parse.setDoc(DOC);
        parse.setVar("parsed");
        TagHarness.run(parse, pc, Body.NONE);

        assertEquals(TRANSFORMED, html(run(transform(pc.getAttribute("parsed"), H4))));
    }

    @Test
    void aOneNodeSetOfXSetIsTransformed() throws Exception {
        Object nodes = set("<a><b>B Text</b><c><d>D Text</d></c></a>", "$d//c");

        assertEquals("<h4>D Text</h4>", html(run(transform(nodes, H4))));
    }

    @Test
    void aTwoNodeSetOfXSetIsATagException() throws Exception {
        TransformTag tag = transform(set("<a><b>1</b><b>2</b></a>", "$d//b"), H4);

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertEquals("x:transform: the XML document must be a single node, not a NodeList of 2 nodes", e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    void aDocumentOfAnotherTypeIsATagException() {
        TransformTag tag = transform(42, H4);

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().startsWith("x:transform: the XML document must be"), e.getMessage());
        assertTrue(e.getMessage().endsWith("java.lang.Integer"), e.getMessage());
    }

    @Test
    void aStylesheetOfAnotherTypeIsATagException() {
        TransformTag tag = transform(DOC, 42);

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().startsWith("x:transform: the XSLT stylesheet must be"), e.getMessage());
    }

    @Test
    @SuppressWarnings("deprecation")
    void theXmlAndXmlSystemIdAttributesAreDeprecatedAliases() throws Exception {
        context.resource("/t/xfiles/resolve.xml", RESOLVE_XML);
        TransformTag tag = transformBody(H4);
        tag.setXml(ENTITY_DOC);
        tag.setXmlSystemId("/t/import.xml");

        assertEquals(RESOLVED, html(run(tag)));
    }

    // ---- syntaxes -----------------------------------------------------------

    @Test
    void syntax3TransformsTheBodyWithItsNestedParameters() throws Exception {
        TransformTag tag = transformBody(PARAMS);

        String out = run(tag, p -> {
            p.getOut().write("\n    <a>REPLACE</a>\n    ");
            param(tag, "first", "1");
            p.getOut().write("\n    ");
            param(tag, "second", "2");
            p.getOut().write("\n");
        });

        assertEquals("[1|2|REPLACE]", out);
    }

    @Test
    void syntax2TakesTheDocAttributeAndABodyOfParametersOnly() throws Exception {
        TransformTag tag = transform("<a>FROM DOC</a>", PARAMS);

        String out = run(tag, p -> {
            p.getOut().write("\n    ");
            param(tag, "first", "1");
            p.getOut().write("\n");
        });

        assertEquals("[1|default-second|FROM DOC]", out);
    }

    @Test
    void theDocAttributeWinsOverTheBodyText() throws Exception {
        TransformTag tag = transform("<a>FROM DOC</a>", PARAMS);

        assertEquals("[default-first|default-second|FROM DOC]", run(tag, p -> p.getOut().write("<a>BODY</a>")));
    }

    // ---- outputs ------------------------------------------------------------

    @Test
    void varAndScopeExportTheResultAsADocument() throws Exception {
        TransformTag tag = transform(DOC, H4);
        tag.setVar("out");
        tag.setScope("request");

        assertEquals("", run(tag));

        Document out = assertInstanceOf(Document.class, pc.getAttribute("out", PageContext.REQUEST_SCOPE));
        assertEquals("h4", out.getDocumentElement().getTagName());
        assertEquals("Arbitrary Text", out.getDocumentElement().getTextContent());
        assertNull(out.getDocumentURI());
        assertNull(pc.getAttribute("out", PageContext.PAGE_SCOPE));
    }

    @Test
    void aResultCapturesTheOutputAndNothingReachesThePage() throws Exception {
        StringWriter captured = new StringWriter();
        TransformTag tag = transform(DOC, H4);
        tag.setResult(new StreamResult(captured));

        assertEquals("", run(tag));

        assertEquals(TRANSFORMED, html(captured.toString()));
    }

    @Test
    void aResultWinsOverVar() throws Exception {
        StringWriter captured = new StringWriter();
        TransformTag tag = transform(DOC, H4);
        tag.setResult(new StreamResult(captured));
        tag.setVar("out");

        assertEquals("", run(tag));

        assertEquals(TRANSFORMED, html(captured.toString()));
        assertNull(pc.getAttribute("out"));
    }

    @Test
    void anInvalidScopeIsATagException() {
        TransformTag tag = transform(DOC, H4);
        tag.setVar("out");
        tag.setScope("galaxy");

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().contains("galaxy"), e.getMessage());
    }

    // ---- null and error handling --------------------------------------------

    @ParameterizedTest(name = "doc ''{0}''")
    @NullAndEmptySource
    void aNullOrEmptyDocIsAJspException(String doc) {
        TransformTag tag = transform(doc, H4);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY_DOC, e.getMessage());
    }

    @ParameterizedTest(name = "xslt ''{0}''")
    @NullAndEmptySource
    void aNullOrEmptyStylesheetIsAJspExceptionBeforeTheDocumentIsRead(String xslt) {
        TransformTag tag = transform("non", xslt);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY_XSLT, e.getMessage());
    }

    @Test
    void noBodyAndNoDocIsAJspException() {
        TransformTag tag = transformBody(H4);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY_DOC, e.getMessage());
    }

    @Test
    void aMalformedStylesheetIsAJspExceptionKeepingTheConfigurationFailure() {
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:template>");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertTrue(e.getMessage().startsWith("x:transform: "), e.getMessage());
        assertInstanceOf(TransformerConfigurationException.class, e.getCause());
    }

    @Test
    void anInvalidSystemIdIsATagExceptionNamingIt() {
        TransformTag tag = transform(DOC, H4);
        tag.setDocSystemId("/../outside.xml");

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertEquals("x:transform: the system id '/../outside.xml' leaves the context root", e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @ParameterizedTest(name = "{0} stylesheet, system id {1}")
    @CsvSource(nullValues = "null", value = {
        "not well-formed, null", "not well-formed, /t/s.xsl", "invalid XSLT, null", "invalid XSLT, /t/s.xsl"})
    void aStylesheetFailureNamesTheSystemIdThePageGaveNotAPrivateBase(String kind, String systemId) {
        String body = kind.equals("invalid XSLT") ? "<xsl:template match='/'><xsl:bogus/></xsl:template>" : "<xsl:template>";
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + ">" + body + "</xsl:stylesheet>");
        tag.setXsltSystemId(systemId);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertNamesNoPrivateBase(e.getMessage());
        assertTrue(e.getMessage().endsWith(", system id '" + (systemId == null ? "(none)" : systemId) + "'"),
                e.getMessage());
    }

    /**
     * The JDK's XSLT compiler reports an error of an included stylesheet without any location
     * (probe: no locator, no system id in the message), so the message names the stylesheet the
     * page gave.
     */
    @Test
    void anIncludedStylesheetFailureNamesTheXsltSystemIdThePageGave() {
        context.resource("/t/inc.xsl", "<xsl:stylesheet " + XSL_NS + "><xsl:template match='/'><xsl:bogus/>"
                + "</xsl:template></xsl:stylesheet>");
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:include href='inc.xsl'/></xsl:stylesheet>");
        tag.setXsltSystemId("/t/s.xsl");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertNamesNoPrivateBase(e.getMessage());
        assertTrue(e.getMessage().endsWith(", system id '/t/s.xsl'"), e.getMessage());
    }

    @ParameterizedTest(name = "system id {0}")
    @CsvSource(nullValues = "null", value = {"null", "/t/d.xml"})
    void aDocumentFailureNamesTheSystemIdThePageGaveNotAPrivateBase(String systemId) {
        TransformTag tag = transform("<a>", H4);
        tag.setDocSystemId(systemId);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertNamesNoPrivateBase(e.getMessage());
        assertTrue(e.getMessage().endsWith(", system id '" + (systemId == null ? "(none)" : systemId) + "'"),
                e.getMessage());
    }

    /**
     * A malformed document is parsed during the transformation, where the JDK's XSLT processor
     * wraps the parse failure without keeping it as a cause: Bodoni's reader records it.
     */
    @Test
    void aMalformedDocumentNamesTheLineAndColumn() {
        TransformTag tag = transform("<a><b></a>", H4);
        tag.setDocSystemId("/t/d.xml");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertTrue(e.getMessage().matches("x:transform: .+, line 1, column 9, system id '/t/d\\.xml'"), e.getMessage());
    }

    /** A malformed stylesheet is parsed when the transformer is compiled; its location is in the cause chain. */
    @Test
    void aMalformedStylesheetNamesTheLineAndColumn() {
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:template match='/'>x</xsl:stylesheet>");
        tag.setXsltSystemId("/t/s.xsl");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertTrue(e.getMessage().matches("x:transform: .+, line 1, column \\d+, system id '/t/s\\.xsl'"), e.getMessage());
    }

    // ---- external references ------------------------------------------------

    @Test
    void aRelativeDocSystemIdResolvesAnEntityOfTheBody() throws Exception {
        context.resource("/t/xfiles/resolve.xml", RESOLVE_XML);
        TransformTag tag = transformBody(H4);
        tag.setDocSystemId("/t/import.xml");

        assertEquals(RESOLVED, html(run(tag, p -> p.getOut().write("\n        " + ENTITY_DOC + "\n    "))));
    }

    @Test
    void withoutADocSystemIdAnEntityIsRefused() {
        TransformTag tag = transform(ENTITY_DOC, H4);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals("x:transform: external reference 'xfiles/resolve.xml' refused, system id '(none)': no system id",
                e.getMessage());
    }

    @Test
    void aRelativeXsltSystemIdResolvesImportsAndIncludes() throws Exception {
        context.resource("/t/xfiles/resolve.xsl", IMPORTED);
        context.resource("/t/inc.xsl", "<xsl:stylesheet " + XSL_NS + ">"
                + "<xsl:template name='included'>included</xsl:template></xsl:stylesheet>");
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:import href='xfiles/resolve.xsl'/>"
                + "<xsl:include href='inc.xsl'/><xsl:output method='text'/><xsl:template match='/'>"
                + "<xsl:call-template name='imported'/>|<xsl:call-template name='included'/></xsl:template>"
                + "</xsl:stylesheet>");
        tag.setXsltSystemId("/t/import.xsl");

        assertEquals("imported|included", run(tag));
    }

    @Test
    void theDocumentFunctionReadsThroughTheXsltSystemId() throws Exception {
        context.resource("/t/data.xml", "<data>from data</data>");
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:output method='text'/>"
                + "<xsl:template match='/'><xsl:value-of select=\"document('data.xml')/data\"/></xsl:template>"
                + "</xsl:stylesheet>");
        tag.setXsltSystemId("/t/import.xsl");

        assertEquals("from data", run(tag));
    }

    @Test
    void anAbsoluteXsltSystemIdFetchesTheImport() throws Exception {
        server = TestHttpServer.start().route("/t/xfiles/resolve.xsl", 200, Map.of(), IMPORTED);
        TransformTag tag = transform(DOC, IMPORTING);
        tag.setXsltSystemId("http://127.0.0.1:" + server.port() + "/t/import.xsl");

        assertEquals("imported", run(tag));
        assertEquals(List.of("/t/xfiles/resolve.xsl"), server.requestedPaths());
    }

    @Test
    void withoutAnXsltSystemIdAnImportIsRefused() {
        TransformTag tag = transform(DOC, IMPORTING);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(REFUSED_IMPORT, e.getMessage());
    }

    @Test
    void aCrossOriginImportIsRefusedWithoutAConnection() {
        server = TestHttpServer.start();
        String target = "http://localhost:" + server.port() + "/t/evil.xsl";
        String systemId = "http://127.0.0.1:" + server.port() + "/t/import.xsl";
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:import href='" + target + "'/>"
                + "</xsl:stylesheet>");
        tag.setXsltSystemId(systemId);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals("x:transform: external reference '" + target + "' refused, system id '" + systemId
                + "': not the same origin", e.getMessage());
        assertTrue(server.requestedPaths().isEmpty());
    }

    @Test
    void anEntityOfAStringStylesheetIsRefusedWithoutXsltSystemId() {
        TransformTag tag = transform(DOC, ENTITY_XSL);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals("x:transform: external reference 'ent.xml' refused, system id '(none)': no system id",
                e.getMessage());
    }

    @Test
    void anEntityOfAStringStylesheetResolvesThroughARelativeXsltSystemId() throws Exception {
        context.resource("/t/ent.xml", "from entity");
        TransformTag tag = transform(DOC, ENTITY_XSL);
        tag.setXsltSystemId("/t/s.xsl");

        assertEquals("from entity", run(tag));
    }

    @Test
    void anEntityOfADocumentFunctionTargetIsUnderThePolicy() {
        context.resource("/t/data.xml", FILE_ENTITY_DOC);
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:output method='text'/>"
                + "<xsl:template match='/'><xsl:value-of select=\"document('data.xml')/r\"/></xsl:template>"
                + "</xsl:stylesheet>");
        tag.setXsltSystemId("/t/s.xsl");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(FILE_ENTITY_REFUSED, e.getMessage());
    }

    @Test
    void anEntityOfAnImportedStylesheetIsUnderThePolicy() {
        context.resource("/t/xfiles/resolve.xsl", "<!DOCTYPE xsl:stylesheet [<!ENTITY e SYSTEM 'file:///etc/hosts'>]>"
                + "<xsl:stylesheet " + XSL_NS + "><xsl:template name='imported'>&e;</xsl:template></xsl:stylesheet>");
        TransformTag tag = transform(DOC, IMPORTING);
        tag.setXsltSystemId("/t/s.xsl");

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(FILE_ENTITY_REFUSED, e.getMessage());
    }

    @Test
    void aCrossOriginEntityOfAnImportedStylesheetIsRefusedWithoutAConnection() {
        server = TestHttpServer.start();
        String target = "http://localhost:" + server.port() + "/e.xml";
        String systemId = "http://127.0.0.1:" + server.port() + "/t/s.xsl";
        server.route("/t/xfiles/resolve.xsl", 200, Map.of(), "<!DOCTYPE xsl:stylesheet [<!ENTITY e SYSTEM '" + target
                + "'>]><xsl:stylesheet " + XSL_NS + "><xsl:template name='imported'>&e;</xsl:template></xsl:stylesheet>");
        TransformTag tag = transform(DOC, IMPORTING);
        tag.setXsltSystemId(systemId);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals("x:transform: external reference '" + target + "' refused, system id '" + systemId
                + "': not the same origin", e.getMessage());
        assertEquals(List.of("/t/xfiles/resolve.xsl"), server.requestedPaths());
    }

    @Test
    void aCrossOriginEntityOfADocumentFunctionTargetIsRefusedWithoutAConnection() {
        server = TestHttpServer.start();
        String target = "http://localhost:" + server.port() + "/e.xml";
        String systemId = "http://127.0.0.1:" + server.port() + "/t/s.xsl";
        server.route("/t/data.xml", 200, Map.of(), "<!DOCTYPE r [<!ENTITY e SYSTEM '" + target + "'>]><r>&e;</r>");
        TransformTag tag = transform(DOC, "<xsl:stylesheet " + XSL_NS + "><xsl:output method='text'/>"
                + "<xsl:template match='/'><xsl:value-of select=\"document('data.xml')/r\"/></xsl:template>"
                + "</xsl:stylesheet>");
        tag.setXsltSystemId(systemId);

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals("x:transform: external reference '" + target + "' refused, system id '" + systemId
                + "': not the same origin", e.getMessage());
        assertEquals(List.of("/t/data.xml"), server.requestedPaths());
    }

    // ---- reuse --------------------------------------------------------------

    @Test
    void aReusedInstanceSeesNoParameterOfItsPreviousRun() throws Exception {
        TransformTag tag = transform("<a>X</a>", PARAMS);

        String first = run(tag, p -> {
            param(tag, "first", "1");
            param(tag, "second", "2");
        });
        String second = run(tag, Body.EMPTY);

        assertEquals("[1|2|X]", first);
        assertEquals("[default-first|default-second|X]", second);
    }

    @Test
    void aReusedInstanceDoesNotReadThePreviousBody() throws Exception {
        TransformTag tag = transformBody(H4);

        assertEquals(TRANSFORMED, html(run(tag, p -> p.getOut().write(DOC))));
        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY_DOC, e.getMessage());
    }

    @Test
    void releaseClearsTheAttributes() {
        TransformTag tag = transform(DOC, H4);
        tag.release();

        JspException e = assertThrows(JspException.class, () -> run(tag));

        assertEquals(NULL_OR_EMPTY_DOC, e.getMessage());
    }

    private static void assertNamesNoPrivateBase(String message) {
        assertFalse(message.contains("bodoni-none"), message);
        assertFalse(message.contains("bodoni-webapp"), message);
    }

    /** {@code out} without the line break the HTML output method ends with. */
    private static String html(String out) {
        return out.stripTrailing();
    }

    /** The node-set {@code x:set} exports for {@code select} over {@code xml} parsed as {@code $d}. */
    private Object set(String xml, String select) throws Exception {
        ParseTag parse = new ParseTag();
        parse.setDoc(xml);
        parse.setVar("d");
        TagHarness.run(parse, pc, Body.NONE);
        SetTag set = new SetTag();
        set.setSelect(select);
        set.setVar("nodes");
        TagHarness.run(set, pc, Body.NONE);
        return pc.getAttribute("nodes");
    }
}
