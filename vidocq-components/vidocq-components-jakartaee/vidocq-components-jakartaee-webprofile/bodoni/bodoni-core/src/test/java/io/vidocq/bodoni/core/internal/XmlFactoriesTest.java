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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathVariableResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

class XmlFactoriesTest {

    private static final String EXTERNAL_ENTITY_DOC =
            "<!DOCTYPE r [<!ENTITY e SYSTEM \"parts/p.xml\">]><r>&e;</r>";

    private static final String ENABLE_EXTENSION_FUNCTIONS = "jdk.xml.enableExtensionFunctions";

    private static final String JAVA_CALL_XSLT =
            "<xsl:stylesheet version='1.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
            + " xmlns:rt='http://xml.apache.org/xalan/java/java.lang.System'>"
            + "<xsl:template match='/'><xsl:value-of select=\"rt:getProperty('user.home')\"/></xsl:template>"
            + "</xsl:stylesheet>";

    private final EntityResolver nullResolver = (publicId, systemId) -> null;
    private final URIResolver nullUriResolver = (href, base) -> null;

    @Test
    void everyFactoryIsSecure() throws Exception {
        assertTrue(XmlFactories.documentBuilderFactory().getFeature(FEATURE_SECURE_PROCESSING));
        assertTrue(XmlFactories.saxParserFactory().getFeature(FEATURE_SECURE_PROCESSING));
        assertTrue(XmlFactories.transformerFactory(nullUriResolver).getFeature(FEATURE_SECURE_PROCESSING));
        assertTrue(XmlFactories.xpathFactory().getFeature(FEATURE_SECURE_PROCESSING));
        assertFalse(XmlFactories.transformerFactory(nullUriResolver).getFeature(ENABLE_EXTENSION_FUNCTIONS));
        assertFalse(XmlFactories.xpathFactory().getFeature(ENABLE_EXTENSION_FUNCTIONS));
    }

    @Test
    void jdkSideFetchingIsClosed() throws Exception {
        assertEquals("", XmlFactories.documentBuilderFactory().getAttribute(ACCESS_EXTERNAL_DTD));
        assertEquals("", XmlFactories.documentBuilderFactory().getAttribute(ACCESS_EXTERNAL_SCHEMA));
        XMLReader reader = XmlFactories.xmlReader(nullResolver);
        assertEquals("", reader.getProperty(ACCESS_EXTERNAL_DTD));
        assertEquals("", reader.getProperty(ACCESS_EXTERNAL_SCHEMA));
        TransformerFactory transformers = XmlFactories.transformerFactory(nullUriResolver);
        assertEquals("", transformers.getAttribute(ACCESS_EXTERNAL_DTD));
        assertEquals("", transformers.getAttribute(ACCESS_EXTERNAL_STYLESHEET));
    }

    @Test
    void documentBuilderIsNamespaceAwareAndNotValidating() {
        DocumentBuilder builder = XmlFactories.documentBuilder(nullResolver);

        assertTrue(builder.isNamespaceAware());
        assertFalse(builder.isValidating());
        assertFalse(builder.isXIncludeAware());
    }

    @Test
    void xmlReaderIsNamespaceAwareAndNotValidating() throws Exception {
        XMLReader reader = XmlFactories.xmlReader(nullResolver);

        assertTrue(reader.getFeature("http://xml.org/sax/features/namespaces"));
        assertFalse(reader.getFeature("http://xml.org/sax/features/validation"));
        assertSame(nullResolver, reader.getEntityResolver());
    }

    @Test
    void resolversAreSet() {
        assertSame(nullUriResolver, XmlFactories.transformerFactory(nullUriResolver).getURIResolver());
    }

    @Test
    void anEntityTheResolverLeavesIsRefused(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("parts"));
        Files.writeString(dir.resolve("parts/p.xml"), "file text");
        String systemId = dir.resolve("doc.xml").toUri().toString();
        DocumentBuilder builder = XmlFactories.documentBuilder(nullResolver);
        XMLReader reader = XmlFactories.xmlReader(nullResolver);

        assertThrows(SAXException.class, () -> builder.parse(source(EXTERNAL_ENTITY_DOC, systemId)));
        assertThrows(SAXException.class, () -> reader.parse(source(EXTERNAL_ENTITY_DOC, systemId)));
    }

    @Test
    void billionLaughsHitsTheJdkLimits() {
        StringBuilder dtd = new StringBuilder("<!DOCTYPE r [<!ENTITY l0 \"lol\">");
        for (int i = 1; i <= 10; i++) {
            dtd.append("<!ENTITY l").append(i).append(" \"").append(("&l" + (i - 1) + ";").repeat(10)).append("\">");
        }
        String xml = dtd.append("]><r>&l10;</r>").toString();
        DocumentBuilder builder = XmlFactories.documentBuilder(nullResolver);

        assertThrows(SAXParseException.class, () -> builder.parse(source(xml, null)));
    }

    @Test
    void emptyDocumentHasNoChild() {
        assertFalse(XmlFactories.emptyDocument().hasChildNodes());
        assertNotSame(XmlFactories.emptyDocument(), XmlFactories.emptyDocument());
    }

    @Test
    void parseErrorsThrowWithoutConsoleOutput() throws Throwable {
        DocumentBuilder builder = XmlFactories.documentBuilder(nullResolver);

        String err = stderrOf(() -> assertThrows(SAXParseException.class, () -> builder.parse(source("<r>", null))));

        assertEquals("", err);
    }

    @Test
    void parseWarningsAreDroppedAndErrorsThrown() throws Throwable {
        ErrorHandler handler = XmlFactories.xmlReader(nullResolver).getErrorHandler();
        SAXParseException problem = new SAXParseException("problem", null);

        String err = stderrOf(() -> assertDoesNotThrow(() -> handler.warning(problem)));

        assertEquals("", err);
        assertThrows(SAXParseException.class, () -> handler.error(problem));
        assertThrows(SAXParseException.class, () -> handler.fatalError(problem));
    }

    @Test
    void transformWarningsAreDroppedAndErrorsThrown() throws Throwable {
        ErrorListener listener = XmlFactories.transformerFactory(nullUriResolver).getErrorListener();
        TransformerException problem = new TransformerException("problem");

        String err = stderrOf(() -> assertDoesNotThrow(() -> listener.warning(problem)));

        assertEquals("", err);
        assertThrows(TransformerException.class, () -> listener.error(problem));
        assertThrows(TransformerException.class, () -> listener.fatalError(problem));
    }

    @Test
    void xpathKeepsItsContextAndRefusesExtensionFunctions() {
        NamespaceContext namespaces = new JavaNamespace();
        XPathVariableResolver variables = name -> "v";
        XPath xpath = XmlFactories.xpath(namespaces, variables);

        assertSame(namespaces, xpath.getNamespaceContext());
        assertSame(variables, xpath.getXPathVariableResolver());
        assertThrows(XPathExpressionException.class,
                () -> xpath.evaluate("java:java.lang.System.nanoTime()", XmlFactories.emptyDocument()));
    }

    @Test
    void extensionsStayOffWhenTheSystemPropertyEnablesThem(@TempDir Path dir) throws Throwable {
        Path written = dir.resolve("written.txt");
        withSystemProperties(Map.of(ENABLE_EXTENSION_FUNCTIONS, "true"), () -> {
            assertThrows(TransformerException.class, () -> transform(JAVA_CALL_XSLT));
            assertThrows(TransformerException.class, () -> transform(redirectXslt(written)));
            XPath xpath = XmlFactories.xpath(new JavaNamespace(), name -> null);
            assertThrows(XPathExpressionException.class,
                    () -> xpath.evaluate("java:java.lang.System.nanoTime()", XmlFactories.emptyDocument()));
            return null;
        });

        assertFalse(Files.exists(written));
    }

    @Test
    void accessSystemPropertiesDoNotReopenJdkSideFetching() throws Throwable {
        withSystemProperties(Map.of(
                "javax.xml.accessExternalDTD", "all",
                "javax.xml.accessExternalSchema", "all",
                "javax.xml.accessExternalStylesheet", "all"), () -> {
                    jdkSideFetchingIsClosed();
                    return null;
                });
    }

    /** Compiles and runs {@code xslt} on a one-element document with a factory of Bodoni. */
    private static String transform(String xslt) throws TransformerException {
        TransformerFactory factory = XmlFactories.transformerFactory((href, base) -> null);
        Transformer transformer = factory.newTransformer(new StreamSource(new StringReader(xslt)));
        transformer.setErrorListener(factory.getErrorListener());
        StringWriter out = new StringWriter();
        transformer.transform(new StreamSource(new StringReader("<a/>")), new StreamResult(out));
        return out.toString();
    }

    /** A stylesheet whose {@code redirect:write} extension element writes {@code target}. */
    private static String redirectXslt(Path target) {
        return "<xsl:stylesheet version='1.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'"
                + " xmlns:redirect='http://xml.apache.org/xalan/redirect' extension-element-prefixes='redirect'>"
                + "<xsl:template match='/'><redirect:write file='" + target + "'>written</redirect:write>"
                + "</xsl:template></xsl:stylesheet>";
    }

    /** Runs {@code body} with {@code values} as system properties, then restores the previous ones. */
    private static <T> T withSystemProperties(Map<String, String> values, ThrowingSupplier<T> body) throws Throwable {
        Map<String, String> previous = new HashMap<>();
        values.keySet().forEach(name -> previous.put(name, System.getProperty(name)));
        try {
            values.forEach(System::setProperty);
            return body.get();
        } finally {
            previous.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
        }
    }

    private static InputSource source(String xml, String systemId) {
        InputSource source = new InputSource(new StringReader(xml));
        source.setSystemId(systemId);
        return source;
    }

    /** Runs {@code body} with {@code System.err} captured; returns what was printed. */
    private static String stderrOf(Executable body) throws Throwable {
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.execute();
        } finally {
            System.setErr(err);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Binds {@code java} to the JDK's Java extension-function namespace. */
    private static final class JavaNamespace implements NamespaceContext {

        @Override
        public String getNamespaceURI(String prefix) {
            return "java".equals(prefix) ? "http://xml.apache.org/xalan/java" : "";
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
