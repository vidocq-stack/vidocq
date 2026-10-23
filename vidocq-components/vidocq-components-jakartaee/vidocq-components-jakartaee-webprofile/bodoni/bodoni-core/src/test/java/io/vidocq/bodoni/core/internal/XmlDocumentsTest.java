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
package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import io.vidocq.bodoni.core.testing.AttributeAddingFilter;
import io.vidocq.bodoni.core.testing.TestPageContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * {@link XmlDocuments}: the {@code x:parse} paths without a tag (Tags 3.0 section 11.2) and the
 * document and stylesheet sources of {@code x:transform} (section 13.2).
 */
class XmlDocumentsTest {

    private static final String ACTION = "x:parse";
    private static final String ABSOLUTE = "http://docs.example/x/doc.xml";
    private static final String NAMESPACED = "<p:a xmlns:p='urn:p'><p:b>1</p:b></p:a>";
    private static final String EXTERNAL = "<!DOCTYPE r [<!ENTITY e SYSTEM 'e.xml'>]><r>&e;</r>";
    private static final String REFUSED =
            "x:parse: external reference 'e.xml' refused, system id '(none)': no system id";

    private final TestPageContext pc = TestPageContext.create();

    private SystemIdPolicy policy(String systemId) {
        return SystemIdPolicy.of(ACTION, systemId, pc);
    }

    @Test
    void withoutAFilterTheDocumentIsParsedNamespaceAware() throws Exception {
        Document doc = XmlDocuments.parse(new StringReader(NAMESPACED), policy(ABSOLUTE), null);

        Element root = doc.getDocumentElement();
        assertEquals("urn:p", root.getNamespaceURI());
        assertEquals("a", root.getLocalName());
        assertEquals("1", root.getTextContent());
        assertEquals(ABSOLUTE, doc.getDocumentURI());
    }

    @Test
    void withAFilterTheFilteredEventsBuildTheDocument() throws Exception {
        Document doc = XmlDocuments.parse(new StringReader(NAMESPACED), policy(ABSOLUTE), new AttributeAddingFilter());

        Element root = doc.getDocumentElement();
        assertEquals("urn:p", root.getNamespaceURI());
        assertEquals("a", root.getLocalName());
        assertEquals("yes", root.getAttribute("seen"));
        assertEquals("yes", ((Element) root.getFirstChild()).getAttribute("seen"));
        assertEquals(ABSOLUTE, doc.getDocumentURI());
    }

    @Test
    void aRelativeSystemIdReachesTheDocumentAsItsWebAppUri() throws Exception {
        Document plain = XmlDocuments.parse(new StringReader("<a/>"), policy("/x/doc.xml"), null);
        Document filtered = XmlDocuments.parse(new StringReader("<a/>"), policy("/x/doc.xml"), new AttributeAddingFilter());

        assertEquals("bodoni-webapp:/x/doc.xml", plain.getDocumentURI());
        assertEquals("bodoni-webapp:/x/doc.xml", filtered.getDocumentURI());
    }

    @Test
    void withoutASystemIdTheDocumentHasNoUri() throws Exception {
        Document plain = XmlDocuments.parse(new StringReader("<a/>"), policy(null), null);
        Document filtered = XmlDocuments.parse(new StringReader("<a/>"), policy(null), new AttributeAddingFilter());

        assertNull(plain.getDocumentURI());
        assertNull(filtered.getDocumentURI());
        assertEquals("a", filtered.getDocumentElement().getTagName());
    }

    /**
     * An {@code XMLFilterImpl} only has the two-argument {@code resolveEntity}: the parser hands
     * it the reference already expanded against the document's base. Without a system id that
     * base must not be the JVM working directory, and the refusal must name the reference as
     * written, as on the path without a filter.
     */
    @Test
    void theFilterRefusesWithTheMessageOfThePathWithoutFilter() {
        SystemIdPolicy filtered = policy(null);
        SystemIdPolicy plain = policy(null);
        AttributeAddingFilter filter = new AttributeAddingFilter();
        StringReader filteredInput = new StringReader(EXTERNAL);
        StringReader plainInput = new StringReader(EXTERNAL);

        assertThrows(TransformerException.class, () -> XmlDocuments.parse(filteredInput, filtered, filter));
        assertThrows(SAXException.class, () -> XmlDocuments.parse(plainInput, plain, null));

        assertEquals(REFUSED, plain.recordedFailure().getMessage());
        assertEquals(REFUSED, filtered.recordedFailure().getMessage());
    }

    @ParameterizedTest(name = "system id {0}")
    @NullSource
    @ValueSource(strings = {"jar:file:/a.jar!/doc.xml", "urn:example:doc"})
    void noReferenceIsExpandedAgainstTheWorkingDirectory(String systemId) {
        String cwd = System.getProperty("user.dir");
        for (AttributeAddingFilter filter : new AttributeAddingFilter[] {null, new AttributeAddingFilter()}) {
            SystemIdPolicy policy = policy(systemId);
            StringReader input = new StringReader(EXTERNAL);

            assertThrows(Exception.class, () -> XmlDocuments.parse(input, policy, filter));

            String message = policy.recordedFailure().getMessage();
            assertTrue(message.contains("e.xml' refused"), message);
            assertFalse(message.contains(cwd), message);
        }
    }

    @Test
    void aMalformedDocumentFailsOnBothPaths() {
        SystemIdPolicy policy = policy(null);
        StringReader plain = new StringReader("<a>");
        StringReader filtered = new StringReader("<a>");
        AttributeAddingFilter filter = new AttributeAddingFilter();

        assertThrows(SAXException.class, () -> XmlDocuments.parse(plain, policy, null));
        assertThrows(TransformerException.class, () -> XmlDocuments.parse(filtered, policy, filter));
        assertNull(policy.recordedFailure());
    }

    // ---- x:transform sources ------------------------------------------------

    @Test
    void aStringDocumentIsReadByAHardenedReaderUnderTheParserSystemId() throws Exception {
        SystemIdPolicy policy = policy("/x/doc.xml");

        SAXSource source = assertInstanceOf(SAXSource.class, XmlDocuments.documentSource("<a/>", policy));

        assertSame(policy, source.getXMLReader().getEntityResolver());
        assertEquals("bodoni-webapp:/x/doc.xml", source.getSystemId());
        assertEquals("<a/>", read(source.getInputSource().getCharacterStream()));
    }

    @Test
    void withoutASystemIdTheSourceGetsThePrivateBase() {
        SAXSource source = assertInstanceOf(SAXSource.class, XmlDocuments.documentSource("<a/>", policy(null)));

        assertEquals(SystemIdPolicy.NONE_BASE, source.getSystemId());
    }

    @Test
    void aReaderDocumentIsReadAsIs() {
        StringReader reader = new StringReader("<a/>");

        SAXSource source = assertInstanceOf(SAXSource.class, XmlDocuments.documentSource(reader, policy(ABSOLUTE)));

        assertSame(reader, source.getInputSource().getCharacterStream());
        assertEquals(ABSOLUTE, source.getSystemId());
    }

    @Test
    void aSourceDocumentIsPassedAsIs() {
        Source given = new StreamSource(new StringReader("<a/>"));

        assertSame(given, XmlDocuments.documentSource(given, policy(null)));
    }

    @Test
    void aNodeDocumentIsADomSourceOfIt() throws Exception {
        Document doc = XmlDocuments.parse(new StringReader("<a><b/></a>"), policy(null), null);
        Node b = doc.getDocumentElement().getFirstChild();

        assertSame(doc, assertInstanceOf(DOMSource.class, XmlDocuments.documentSource(doc, policy(null))).getNode());
        assertSame(b, assertInstanceOf(DOMSource.class, XmlDocuments.documentSource(b, policy(null))).getNode());
    }

    @Test
    void aNodeListOfOneNodeIsADomSourceOfThatNode() throws Exception {
        Document doc = XmlDocuments.parse(new StringReader("<a><b/></a>"), policy(null), null);
        Node b = doc.getDocumentElement().getFirstChild();

        DOMSource source = assertInstanceOf(DOMSource.class, XmlDocuments.documentSource(NodeSet.of(List.of(b)), policy(null)));

        assertSame(b, source.getNode());
    }

    @ParameterizedTest(name = "{0} nodes")
    @ValueSource(ints = {0, 2})
    void aNodeListOfAnotherLengthIsRefusedNamingTheLength(int length) throws Exception {
        Document doc = XmlDocuments.parse(new StringReader("<a><b/><b/></a>"), policy(null), null);
        NodeList all = doc.getElementsByTagName("b");
        NodeSet nodes = NodeSet.of(length == 0 ? List.of() : List.of(all.item(0), all.item(1)));
        SystemIdPolicy policy = policy(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> XmlDocuments.documentSource(nodes, policy));

        assertEquals("the XML document must be a single node, not a NodeList of " + length + " nodes", e.getMessage());
    }

    @Test
    void aDocumentOfAnotherTypeIsRefusedNamingTheType() {
        SystemIdPolicy policy = policy(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> XmlDocuments.documentSource(42, policy));

        assertEquals("the XML document must be a String, a Reader, a javax.xml.transform.Source, an org.w3c.dom.Node "
                + "or a NodeList of one node, not java.lang.Integer", e.getMessage());
    }

    @Test
    void aStringOrReaderStylesheetIsReadByAHardenedReaderUnderTheParserSystemId() {
        SystemIdPolicy policy = policy("/x/t.xsl");
        StringReader reader = new StringReader("<s/>");

        SAXSource fromString = assertInstanceOf(SAXSource.class, XmlDocuments.stylesheetSource("<s/>", policy));
        SAXSource fromReader = assertInstanceOf(SAXSource.class, XmlDocuments.stylesheetSource(reader, policy));

        assertSame(policy, fromString.getXMLReader().getEntityResolver());
        assertEquals("bodoni-webapp:/x/t.xsl", fromString.getSystemId());
        assertSame(reader, fromReader.getInputSource().getCharacterStream());
        assertEquals("bodoni-webapp:/x/t.xsl", fromReader.getSystemId());
    }

    @Test
    void aSourceStylesheetIsPassedAsIs() {
        Source given = new StreamSource(new StringReader("<s/>"));

        assertSame(given, XmlDocuments.stylesheetSource(given, policy(null)));
    }

    @Test
    void aStylesheetOfAnotherTypeIsRefusedNamingTheType() throws Exception {
        Document doc = XmlDocuments.parse(new StringReader("<s/>"), policy(null), null);
        SystemIdPolicy policy = policy(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> XmlDocuments.stylesheetSource(doc, policy));

        assertTrue(e.getMessage().startsWith(
                "the XSLT stylesheet must be a String, a Reader or a javax.xml.transform.Source, not "), e.getMessage());
    }

    private static String read(Reader reader) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int c = reader.read(); c != -1; c = reader.read()) {
            text.append((char) c);
        }
        return text.toString();
    }
}
