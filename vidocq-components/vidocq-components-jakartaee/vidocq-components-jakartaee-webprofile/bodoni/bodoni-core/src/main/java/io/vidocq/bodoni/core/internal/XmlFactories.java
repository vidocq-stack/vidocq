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

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;
import javax.xml.xpath.XPathVariableResolver;

import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * The JAXP objects of the {@code x:} library, hardened for the "same origin" policy on XML
 * external references (Tags 3.0 section 11.1.7, PLAN.md open question 6).
 *
 * <p>Every factory comes from the JDK's {@code newDefaultInstance()} /
 * {@code newDefaultNSInstance()} (no {@code ServiceLoader} lookup), has
 * {@link javax.xml.XMLConstants#FEATURE_SECURE_PROCESSING} on (JDK expansion limits), XSLT and
 * XPath extension functions off and the JDK access properties set to {@code ""}: the JDK opens
 * no external reference and calls no extension on its own, whatever a system property or
 * {@code jaxp.properties} says. External references reach the content only through the resolver handed in, which
 * returns an opened stream (probe: {@code JdkXmlFactsTest}). That resolver must refuse by
 * throwing, never by returning {@code null}: under a {@code bodoni-webapp:} base the JDK
 * drops an {@code xsl:import} whose resolver answered {@code null} without an error.
 *
 * <p>Parsing is namespace-aware (XPath 1.0 semantics), not validating, XInclude off; a
 * DOCTYPE with an internal subset is accepted. Warnings are dropped, errors and fatal errors
 * are thrown: nothing is printed on {@code System.err}.
 *
 * <p>JAXP objects are not thread-safe: each call creates new ones and nothing is shared.
 * A JAXP configuration failure means a broken JDK, not a page error, and is thrown as an
 * {@link IllegalStateException}.
 */
public final class XmlFactories {

    /** Access-property value that allows no protocol. */
    private static final String NO_ACCESS = "";

    /**
     * The JDK feature that allows XSLT and XPath extension functions (java.xml module
     * summary, implementation-specific features). Secure processing turns it off by default,
     * but the system property of the same name turns it back on: Bodoni sets it explicitly.
     */
    static final String ENABLE_EXTENSION_FUNCTIONS = "jdk.xml.enableExtensionFunctions";

    /** The shared error rule; stateless, hence safe to share. */
    private static final Strict STRICT = new Strict();

    private XmlFactories() {
    }

    /**
     * A new document builder.
     *
     * @param resolver the resolver of every external entity and of the external DTD subset
     * @return a namespace-aware, non-validating builder that resolves through {@code resolver}
     */
    public static DocumentBuilder documentBuilder(EntityResolver resolver) {
        DocumentBuilder builder = newDocumentBuilder();
        builder.setEntityResolver(resolver);
        builder.setErrorHandler(STRICT);
        return builder;
    }

    /**
     * A new SAX reader, for {@code x:parse} filters and {@code SAXSource} documents.
     *
     * @param resolver the resolver of every external entity and of the external DTD subset
     * @return a namespace-aware, non-validating reader that resolves through {@code resolver}
     */
    public static XMLReader xmlReader(EntityResolver resolver) {
        try {
            SAXParser parser = saxParserFactory().newSAXParser();
            parser.setProperty(ACCESS_EXTERNAL_DTD, NO_ACCESS);
            parser.setProperty(ACCESS_EXTERNAL_SCHEMA, NO_ACCESS);
            XMLReader reader = parser.getXMLReader();
            reader.setEntityResolver(resolver);
            reader.setErrorHandler(STRICT);
            return reader;
        } catch (ParserConfigurationException | SAXException e) {
            throw broken(e);
        }
    }

    /**
     * A new XSLT factory. A {@code Transformer} it creates gets the factory's
     * {@code URIResolver} but not its {@code ErrorListener}.
     *
     * @param resolver the resolver of {@code xsl:import} and {@code xsl:include}, and the
     *                 default one of {@code document()}
     * @return a secure factory that resolves through {@code resolver}
     */
    public static TransformerFactory transformerFactory(URIResolver resolver) {
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        try {
            factory.setFeature(FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(ENABLE_EXTENSION_FUNCTIONS, false);
        } catch (TransformerConfigurationException e) {
            throw broken(e);
        }
        factory.setAttribute(ACCESS_EXTERNAL_DTD, NO_ACCESS);
        factory.setAttribute(ACCESS_EXTERNAL_STYLESHEET, NO_ACCESS);
        factory.setURIResolver(resolver);
        factory.setErrorListener(STRICT);
        return factory;
    }

    /**
     * A new XPath evaluator from a secure factory.
     *
     * @param namespaces the prefix bindings of the expressions, not {@code null}
     * @param variables  the resolver of {@code $name} references, not {@code null}
     * @return an evaluator that refuses extension functions
     */
    public static XPath xpath(NamespaceContext namespaces, XPathVariableResolver variables) {
        XPath xpath = xpathFactory().newXPath();
        xpath.setNamespaceContext(namespaces);
        xpath.setXPathVariableResolver(variables);
        return xpath;
    }

    /**
     * A new document without any child, the default XPath context of section 11.1.6.
     *
     * @return an empty document
     */
    public static Document emptyDocument() {
        return newDocumentBuilder().newDocument();
    }

    /** The secure, namespace-aware, non-validating DOM factory behind {@link #documentBuilder}. */
    static DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
        try {
            factory.setFeature(FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw broken(e);
        }
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setAttribute(ACCESS_EXTERNAL_DTD, NO_ACCESS);
        factory.setAttribute(ACCESS_EXTERNAL_SCHEMA, NO_ACCESS);
        return factory;
    }

    /** The secure, namespace-aware, non-validating SAX factory behind {@link #xmlReader}. */
    static SAXParserFactory saxParserFactory() {
        SAXParserFactory factory = SAXParserFactory.newDefaultNSInstance();
        try {
            factory.setFeature(FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException | SAXException e) {
            throw broken(e);
        }
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        return factory;
    }

    /** The secure XPath factory behind {@link #xpath}. */
    static XPathFactory xpathFactory() {
        XPathFactory factory = XPathFactory.newDefaultInstance();
        try {
            factory.setFeature(FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(ENABLE_EXTENSION_FUNCTIONS, false);
        } catch (XPathFactoryConfigurationException e) {
            throw broken(e);
        }
        return factory;
    }

    private static DocumentBuilder newDocumentBuilder() {
        try {
            return documentBuilderFactory().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw broken(e);
        }
    }

    private static IllegalStateException broken(Exception cause) {
        return new IllegalStateException("The JDK XML configuration failed: " + cause.getMessage(), cause);
    }

    /** Drops warnings, throws errors and fatal errors, for SAX and for XSLT alike. */
    private static final class Strict implements ErrorHandler, ErrorListener {

        @Override
        public void warning(SAXParseException exception) {
            // Dropped: a warning does not stop the action and must not reach the console.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void warning(TransformerException exception) {
            // Dropped: a warning does not stop the action and must not reach the console.
        }

        @Override
        public void error(TransformerException exception) throws TransformerException {
            throw exception;
        }

        @Override
        public void fatalError(TransformerException exception) throws TransformerException {
            throw exception;
        }
    }
}
