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

import static javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD;
import static javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA;
import static javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET;
import static javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathEvaluationResult.XPathResultType;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.EntityResolver2;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * JDK probe behind PLAN.md open question 6 (Tags 3.0 section 11.1.7): records, against raw
 * JAXP configured exactly as {@link XmlFactories} configures it ({@code FEATURE_SECURE_PROCESSING}
 * true, access properties {@code ""}), the JDK behaviours the "same origin" design relies on.
 * Kept as a regression test: a JDK that changes one of these facts fails here first.
 */
class JdkXmlFactsTest {

    private static final String WEBAPP_DOC = "bodoni-webapp:/dir/doc.xml";
    private static final String WEBAPP_PART = "bodoni-webapp:/dir/parts/p.xml";
    private static final String WEBAPP_XSL = "bodoni-webapp:/xsl/main.xsl";
    private static final String ENTITY_TEXT = "entity text";
    private static final String EXTERNAL_ENTITY_DOC =
            "<!DOCTYPE r [<!ENTITY e SYSTEM \"parts/p.xml\">]><r>&e;</r>";
    private static final String XSL_OPEN =
            "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">"
                    + "<xsl:output method=\"text\"/>";
    private static final String XSL_CLOSE = "</xsl:stylesheet>";

    /** Drops warnings, throws errors and fatal errors: no console output. */
    private static final ErrorHandler STRICT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // dropped
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    /** The {@link #STRICT} rule for XSLT. */
    private static final ErrorListener STRICT_LISTENER = new ErrorListener() {
        @Override
        public void warning(TransformerException exception) {
            // dropped
        }

        @Override
        public void error(TransformerException exception) throws TransformerException {
            throw exception;
        }

        @Override
        public void fatalError(TransformerException exception) throws TransformerException {
            throw exception;
        }
    };

    @Test
    void doctypeWithInternalSubsetParses() throws Exception {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE test [<!ELEMENT test (docs)> <!ELEMENT docs EMPTY>]>"
                + "<test><docs>DATA</docs></test>";

        Document document = documentBuilder(new Resolver(Map.of())).parse(source(xml, null));

        assertEquals("DATA", XPathFactory.newDefaultInstance().newXPath().evaluate("//docs", document));
    }

    @Test
    void resolverStreamPassesUnderEmptyAccess() throws Exception {
        Resolver resolver = new Resolver(Map.of(WEBAPP_PART, ENTITY_TEXT));

        Document document = documentBuilder(resolver).parse(source(EXTERNAL_ENTITY_DOC, WEBAPP_DOC));

        assertEquals(ENTITY_TEXT, document.getDocumentElement().getTextContent());
    }

    @Test
    void nullResolverResultIsRefusedUnderEmptyAccess(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("parts"));
        Files.writeString(dir.resolve("parts/p.xml"), "file text");
        Resolver resolver = new Resolver(Map.of());
        DocumentBuilder builder = documentBuilder(resolver);
        InputSource input = source(EXTERNAL_ENTITY_DOC, dir.resolve("doc.xml").toUri().toString());

        SAXException refused = assertThrows(SAXException.class, () -> builder.parse(input));

        // The resolver was asked, answered null, and the JDK refused to open the file itself.
        assertEquals(List.of("null parts/p.xml " + dir.resolve("doc.xml").toUri()), resolver.calls);
        assertTrue(refused.getMessage().contains("accessExternalDTD"), refused.getMessage());
    }

    @Test
    void entityResolver2SeesTheLiteralAndTheBase() throws Exception {
        String xml = "<!DOCTYPE r SYSTEM \"r.dtd\" ["
                + "<!ENTITY % p SYSTEM \"parts/p.xml\"> %p;"
                + "]><r>&q;</r>";
        Resolver resolver = new Resolver(Map.of(
                WEBAPP_PART, "<!ENTITY q SYSTEM \"q.xml\">",
                "bodoni-webapp:/dir/parts/q.xml", "nested",
                "bodoni-webapp:/dir/r.dtd", ""));

        Document document = documentBuilder(resolver).parse(source(xml, WEBAPP_DOC));

        assertEquals("nested", document.getDocumentElement().getTextContent());
        // Literal and base as expected; the JDK passes a null name for every entity, the
        // external DTD subset included (EntityResolver2 documents "[dtd]" and "%p").
        assertEquals(List.of(
                "null parts/p.xml " + WEBAPP_DOC,
                "null r.dtd " + WEBAPP_DOC,
                "null q.xml " + WEBAPP_PART), resolver.calls);
    }

    @Test
    void uriResolverServesImportsUnderEmptyStylesheetAccess() throws Exception {
        String main = XSL_OPEN.replace("<xsl:output", "<xsl:import href=\"common.xsl\"/><xsl:output") + XSL_CLOSE;
        String common = XSL_OPEN + "<xsl:template match=\"/\">imported</xsl:template>" + XSL_CLOSE;
        UriRecorder resolver = new UriRecorder(Map.of("bodoni-webapp:/xsl/common.xsl", common));

        Transformer transformer = transformerFactory(resolver).newTransformer(new StreamSource(new StringReader(main), WEBAPP_XSL));

        assertEquals("imported", transform(transformer, new StreamSource(new StringReader("<in/>"))));
        assertEquals(List.of("common.xsl " + WEBAPP_XSL), resolver.calls);
    }

    @Test
    void nullUriResolverResultIsRefusedOnlyForAFetchableScheme(@TempDir Path dir) throws Exception {
        String main = XSL_OPEN.replace("<xsl:output", "<xsl:import href=\"common.xsl\"/><xsl:output") + XSL_CLOSE;
        String fileBase = dir.resolve("main.xsl").toUri().toString();
        Files.writeString(dir.resolve("common.xsl"), XSL_OPEN + "<xsl:template match=\"/\">imported</xsl:template>" + XSL_CLOSE);
        TransformerFactory refusing = transformerFactory(new UriRecorder(Map.of()));
        StreamSource fileStylesheet = new StreamSource(new StringReader(main), fileBase);

        TransformerException refused = assertThrows(TransformerException.class, () -> refusing.newTransformer(fileStylesheet));
        assertTrue(refused.getMessage().contains("accessExternalStylesheet"), refused.getMessage());

        // Under a bodoni-webapp: base the JDK's access check fails on the unknown scheme: the
        // import is dropped without an error and a stack trace goes to System.err.
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Transformer dropped;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            dropped = refusing.newTransformer(new StreamSource(new StringReader(main), WEBAPP_XSL));
        } finally {
            System.setErr(err);
        }
        assertEquals("x", transform(dropped, new StreamSource(new StringReader("<in>x</in>"))));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("MalformedURLException: unknown protocol: bodoni-webapp"));

        // A resolver that throws is the only way to refuse whatever the base.
        TransformerFactory throwing = transformerFactory((href, base) -> {
            throw new TransformerException("refused " + href);
        });
        StreamSource webappStylesheet = new StreamSource(new StringReader(main), WEBAPP_XSL);
        TransformerException thrown = assertThrows(TransformerException.class, () -> throwing.newTransformer(webappStylesheet));
        assertTrue(thrown.getMessage().contains("refused common.xsl"), thrown.getMessage());
    }

    @Test
    void documentFunctionGoesThroughTheTransformerResolver() throws Exception {
        String main = XSL_OPEN + "<xsl:template match=\"/\"><xsl:value-of select=\"document('data.xml')/d\"/></xsl:template>"
                + XSL_CLOSE;
        UriRecorder factoryResolver = new UriRecorder(Map.of());
        UriRecorder transformerResolver = new UriRecorder(Map.of("bodoni-webapp:/xsl/data.xml", "<d>from data</d>"));

        Transformer transformer = transformerFactory(factoryResolver)
                .newTransformer(new StreamSource(new StringReader(main), WEBAPP_XSL));
        transformer.setURIResolver(transformerResolver);

        assertEquals("from data", transform(transformer, new StreamSource(new StringReader("<in/>"))));
        assertEquals(List.of(), factoryResolver.calls);
        assertEquals(List.of("data.xml " + WEBAPP_XSL), transformerResolver.calls);
        // The Transformer does not inherit the factory's ErrorListener.
        assertNotSame(STRICT_LISTENER, transformer.getErrorListener());
    }

    @Test
    void saxSourceReaderKeepsItsEntityResolverDuringTransform() throws Exception {
        Resolver resolver = new Resolver(Map.of(WEBAPP_PART, ENTITY_TEXT));
        String rootText = XSL_OPEN + "<xsl:template match=\"/\"><xsl:value-of select=\"/r\"/></xsl:template>" + XSL_CLOSE;
        Transformer transformer = transformerFactory(new UriRecorder(Map.of()))
                .newTransformer(new StreamSource(new StringReader(rootText), WEBAPP_XSL));

        String out = transform(transformer, new SAXSource(xmlReader(resolver), source(EXTERNAL_ENTITY_DOC, WEBAPP_DOC)));

        assertEquals(ENTITY_TEXT, out);
        assertEquals(List.of("null parts/p.xml " + WEBAPP_DOC), resolver.calls);
    }

    @Test
    void filterNeedsTheResolverOnItself() throws Exception {
        Resolver resolver = new Resolver(Map.of(WEBAPP_PART, ENTITY_TEXT));
        XMLFilterImpl filter = new XMLFilterImpl(xmlReader(resolver));
        filter.setErrorHandler(STRICT);
        Text text = new Text();
        filter.setContentHandler(text);
        InputSource refusedInput = source(EXTERNAL_ENTITY_DOC, WEBAPP_DOC);

        // The JDK's access check fails on the unknown scheme before anything is opened.
        MalformedURLException refused = assertThrows(MalformedURLException.class, () -> filter.parse(refusedInput));
        assertTrue(refused.getMessage().contains("bodoni-webapp"), refused.getMessage());
        assertEquals(List.of(), resolver.calls);

        filter.setEntityResolver(resolver);
        filter.parse(source(EXTERNAL_ENTITY_DOC, WEBAPP_DOC));

        assertEquals(ENTITY_TEXT, text.text.toString());
        // XMLFilterImpl is a plain EntityResolver: the resolver gets the expanded system id only.
        assertEquals(List.of("2-arg " + WEBAPP_PART), resolver.calls);
    }

    @Test
    void xpathKeepsVariablesAndRefusesExtensionFunctions() throws Exception {
        XPathFactory factory = XPathFactory.newDefaultInstance();
        factory.setFeature(FEATURE_SECURE_PROCESSING, true);
        XPath xpath = factory.newXPath();
        xpath.setNamespaceContext(new Namespaces(Map.of("p", "urn:p", "java", "http://xml.apache.org/xalan/java")));
        xpath.setXPathVariableResolver(name -> name.equals(new QName("urn:p", "x")) ? "vx" : null);
        Document document = documentBuilder(new Resolver(Map.of())).parse(source("<a><b/></a>", null));

        assertEquals("vx", xpath.evaluate("$p:x", document));
        assertThrows(XPathExpressionException.class, () -> xpath.evaluate("$y", document));
        assertThrows(XPathExpressionException.class, () -> xpath.evaluate("java:java.lang.System.nanoTime()", document));
        assertEquals(XPathResultType.BOOLEAN, xpath.evaluateExpression("true()", document).type());
        assertEquals(XPathResultType.NUMBER, xpath.evaluateExpression("1", document).type());
        assertEquals(XPathResultType.STRING, xpath.evaluateExpression("'a'", document).type());
        assertEquals(XPathResultType.NODESET, xpath.evaluateExpression("//b", document).type());
    }

    private static DocumentBuilder documentBuilder(EntityResolver resolver) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
        factory.setFeature(FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(resolver);
        builder.setErrorHandler(STRICT);
        return builder;
    }

    private static XMLReader xmlReader(EntityResolver resolver) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newDefaultNSInstance();
        factory.setFeature(FEATURE_SECURE_PROCESSING, true);
        SAXParser parser = factory.newSAXParser();
        parser.setProperty(ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(ACCESS_EXTERNAL_SCHEMA, "");
        XMLReader reader = parser.getXMLReader();
        reader.setEntityResolver(resolver);
        reader.setErrorHandler(STRICT);
        return reader;
    }

    private static TransformerFactory transformerFactory(URIResolver resolver) throws Exception {
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        factory.setFeature(FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(ACCESS_EXTERNAL_STYLESHEET, "");
        factory.setURIResolver(resolver);
        factory.setErrorListener(STRICT_LISTENER);
        return factory;
    }

    private static String transform(Transformer transformer, Source input) throws TransformerException {
        StringWriter out = new StringWriter();
        transformer.transform(input, new StreamResult(out));
        return out.toString();
    }

    private static InputSource source(String xml, String systemId) {
        InputSource source = new InputSource(new StringReader(xml));
        source.setSystemId(systemId);
        return source;
    }

    private static InputSource bytes(String body, String systemId) {
        InputSource source = new InputSource(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        source.setSystemId(systemId);
        return source;
    }

    /** Records every call as {@code "name literal base"} (or {@code "2-arg systemId"}); serves bodies by absolute URI. */
    private static final class Resolver implements EntityResolver2 {

        private final Map<String, String> bodies;
        private final List<String> calls = new ArrayList<>();

        Resolver(Map<String, String> bodies) {
            this.bodies = bodies;
        }

        @Override
        public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) {
            calls.add(name + " " + systemId + " " + baseURI);
            String absolute = baseURI == null ? systemId : URI.create(baseURI).resolve(systemId).toString();
            return serve(absolute);
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            calls.add("2-arg " + systemId);
            return serve(systemId);
        }

        @Override
        public InputSource getExternalSubset(String name, String baseURI) {
            return null;
        }

        private InputSource serve(String absolute) {
            String body = bodies.get(absolute);
            return body == null ? null : bytes(body, absolute);
        }
    }

    /** Records every call as {@code "href base"}; serves bodies by absolute URI as a byte-stream {@link SAXSource}. */
    private static final class UriRecorder implements URIResolver {

        private final Map<String, String> bodies;
        private final List<String> calls = new ArrayList<>();

        UriRecorder(Map<String, String> bodies) {
            this.bodies = bodies;
        }

        @Override
        public Source resolve(String href, String base) {
            calls.add(href + " " + base);
            String absolute = URI.create(base).resolve(href).toString();
            String body = bodies.get(absolute);
            return body == null ? null : new SAXSource(bytes(body, absolute));
        }
    }

    /** Collects character data. */
    private static final class Text extends DefaultHandler {

        private final StringBuilder text = new StringBuilder();

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }
    }

    /** A fixed prefix-to-URI {@link NamespaceContext}. */
    private record Namespaces(Map<String, String> uris) implements NamespaceContext {

        @Override
        public String getNamespaceURI(String prefix) {
            return uris.getOrDefault(prefix, "");
        }

        @Override
        public String getPrefix(String namespaceURI) {
            return null;
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            return List.<String>of().iterator();
        }
    }
}
