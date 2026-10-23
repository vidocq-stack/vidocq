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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;

import javax.xml.xpath.XPathExpressionException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

/** XPath evaluation over the page scopes: sections 11.1.2 to 11.1.4 and 11.1.6. */
class XPathEngineTest {

    private static final String STUFF = """
            <stuff>
              <node1><person><name>text from first node set</name></person></node1>
              <node2><person><name>text from second node set</name></person></node2>
            </stuff>""";

    private static final String SERVER = """
            <Server port="8005">
              <GlobalNamingResources>
                <ResourceParams name="UserDatabase">
                  <parameter><name>factory</name><value>some.factory</value></parameter>
                  <parameter><name>pathname</name><value>users.xml</value></parameter>
                </ResourceParams>
              </GlobalNamingResources>
            </Server>""";

    private final TestServletContext context = new TestServletContext();
    private final TestHttpServletRequest request = new TestHttpServletRequest(context);
    private final TestPageContext pc = new TestPageContext(request, new TestHttpServletResponse(), context);

    // ---- section 11.1.2: the spec example ------------------------------------

    @Test
    void specExampleSelectsTheBarMatchingARequestParameter() throws Exception {
        Document doc = parse("<foo><bar x='1' n='one'/><bar x='2' n='two'/></foo>");
        pc.setAttribute("doc", doc);
        request.parameter("name", "2");

        assertEquals("two", XPathEngine.string(pc, null, "$doc/foo/bar[@x=$param:name]/@n"));
        assertEquals("two", XPathEngine.string(pc, doc, "/foo/bar[@x=$param:name]/@n"));
        List<Node> bars = XPathEngine.nodes(pc, null, "$doc/foo/bar[@x=$param:name]");
        assertEquals(1, bars.size());
        assertEquals("two", ((Element) bars.getFirst()).getAttribute("n"));
    }

    // ---- section 11.1.3: unbound variables fail ------------------------------

    @Test
    void unboundVariableFails() {
        assertThrows(XPathExpressionException.class, () -> XPathEngine.string(pc, null, "$nope"));
    }

    @Test
    void absentParameterFails() {
        assertThrows(XPathExpressionException.class, () -> XPathEngine.string(pc, null, "$param:absent"));
    }

    @Test
    void unknownPrefixFails() {
        pc.setAttribute("bar", "page");

        assertThrows(XPathExpressionException.class, () -> XPathEngine.string(pc, null, "$foo:bar"));
    }

    @Test
    void sessionScopeWithoutASessionFailsAndCreatesNone() {
        request.withoutSession();

        assertThrows(XPathExpressionException.class, () -> XPathEngine.string(pc, null, "$sessionScope:x"));
        assertFalse(request.hasSession());
    }

    @Test
    void nullOrEmptySelectFails() {
        assertThrows(XPathExpressionException.class, () -> XPathEngine.select(pc, null, null));
        assertThrows(XPathExpressionException.class, () -> XPathEngine.select(pc, null, ""));
        assertThrows(XPathExpressionException.class, () -> XPathEngine.string(pc, null, null));
        assertThrows(XPathExpressionException.class, () -> XPathEngine.bool(pc, null, ""));
        assertThrows(XPathExpressionException.class, () -> XPathEngine.nodes(pc, null, null));
    }

    @Test
    void malformedSelectFails() {
        assertThrows(XPathExpressionException.class, () -> XPathEngine.select(pc, null, "/foo["));
    }

    // ---- section 11.1.3: Java to XPath (TCK positiveJavaToXPathTypesTest) -----

    @Test
    void numbersAreXPathNumbers() throws Exception {
        pc.setAttribute("i", 1);
        pc.setAttribute("l", 3L);
        pc.setAttribute("d", new BigDecimal("2.5"));

        assertEquals("1", XPathEngine.string(pc, null, "string(number($pageScope:i))"));
        assertEquals("3", XPathEngine.string(pc, null, "string(number($pageScope:l))"));
        assertEquals("2.5", XPathEngine.string(pc, null, "string(number($pageScope:d))"));
        assertEquals(4.0, XPathEngine.select(pc, null, "$i + $l"));
    }

    @Test
    void booleansAreXPathBooleans() throws Exception {
        pc.setAttribute("b", Boolean.TRUE);
        pc.setAttribute("f", Boolean.FALSE);

        assertTrue(XPathEngine.bool(pc, null, "boolean($b)"));
        assertFalse(XPathEngine.bool(pc, null, "boolean($f)"));
    }

    @Test
    void stringsAreXPathStrings() throws Exception {
        pc.setAttribute("s", "name");

        assertEquals("name", XPathEngine.string(pc, null, "string($pageScope:s)"));
        assertEquals(4.0, XPathEngine.select(pc, null, "string-length($s)"));
    }

    @Test
    void documentsAndElementsAreNodeSets() throws Exception {
        Document doc = parse("<a><b>in b</b></a>");
        pc.setAttribute("doc", doc);
        pc.setAttribute("el", doc.getDocumentElement());

        assertEquals("in b", XPathEngine.string(pc, null, "$doc/a/b"));
        assertEquals("in b", XPathEngine.string(pc, null, "$el/b"));
    }

    @Test
    void aSelectedNodeSetIsANodeSetAgain() throws Exception {
        pc.setAttribute("doc", parse(STUFF));
        Object nodes = XPathEngine.select(pc, null, "$doc//node1");
        pc.setAttribute("nodes", nodes);

        assertEquals("text from first node set", XPathEngine.string(pc, null, "$nodes/person/name"));
        assertEquals("text from first node set", XPathEngine.string(pc, null, "$nodes//name"));
    }

    @Test
    void otherObjectsAreTheirString() throws Exception {
        pc.setAttribute("o", new StringBuilder("sb"));

        assertEquals("sb", XPathEngine.string(pc, null, "string($o)"));
    }

    // ---- section 11.1.4: XPath to Java (TCK positiveXPathToJavaTypesTest) -----

    @Test
    void resultsMapToJavaTypes() throws Exception {
        pc.setAttribute("doc", parse(SERVER));

        assertEquals(Boolean.TRUE, XPathEngine.select(pc, null,
                "boolean($doc/Server/GlobalNamingResources/ResourceParams)"));
        assertEquals(2.0, XPathEngine.select(pc, null,
                "count($doc/Server/GlobalNamingResources/ResourceParams/parameter)"));
        assertInstanceOf(String.class, XPathEngine.select(pc, null, "string($doc/*)"));
        NodeSet parameters = assertInstanceOf(NodeSet.class, XPathEngine.select(pc, null, "$doc//parameter"));
        assertEquals(2, parameters.getLength());
        assertEquals("parameter", parameters.item(1).getNodeName());
    }

    @Test
    void stringAndBoolApplyTheXPathConversions() throws Exception {
        pc.setAttribute("doc", parse(SERVER));

        assertEquals("factory", XPathEngine.string(pc, null, "$doc//parameter/name"));
        assertEquals("2", XPathEngine.string(pc, null, "count($doc//parameter)"));
        assertTrue(XPathEngine.bool(pc, null, "$doc//parameter"));
        assertFalse(XPathEngine.bool(pc, null, "$doc//absent"));
        assertTrue(XPathEngine.bool(pc, null, "'false'"));
    }

    @Test
    void nodesListsTheSelectedNodesInDocumentOrder() throws Exception {
        pc.setAttribute("doc", parse(SERVER));

        List<Node> names = XPathEngine.nodes(pc, null, "$doc//parameter/name");

        assertEquals(List.of("factory", "pathname"), names.stream().map(Node::getTextContent).toList());
        assertThrows(UnsupportedOperationException.class, names::clear);
    }

    @Test
    void nodesOfANonNodeSetFails() {
        assertThrows(XPathExpressionException.class, () -> XPathEngine.nodes(pc, null, "1"));
    }

    // ---- section 11.1.6: the default context node ----------------------------

    @Test
    void defaultContextIsTheRootOfAnEmptyDocument() throws Exception {
        assertEquals(0.0, XPathEngine.select(pc, null, "count(/*)"));
        assertEquals("", XPathEngine.string(pc, null, "/"));
        assertEquals(List.of(), XPathEngine.nodes(pc, null, "/*"));
        assertEquals(Node.DOCUMENT_NODE, XPathEngine.nodes(pc, null, "/").getFirst().getNodeType());
    }

    @Test
    void aGivenContextNodeIsUsed() throws Exception {
        Document doc = parse(STUFF);

        assertEquals(1.0, XPathEngine.select(pc, doc, "count(/*)"));
        assertEquals("text from second node set", XPathEngine.string(pc, doc.getDocumentElement(), "node2"));
    }

    /** Parses {@code xml} with Bodoni's builder; any external reference is refused. */
    static Document parse(String xml) throws Exception {
        return XmlFactories.documentBuilder((publicId, systemId) -> {
            throw new SAXException("no external reference in this test: " + systemId);
        }).parse(new InputSource(new StringReader(xml)));
    }
}
