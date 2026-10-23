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

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;

/**
 * The XML documents of the {@code x:} library, read with the hardened JAXP objects of
 * {@link XmlFactories} and the external references resolved by a {@link SystemIdPolicy}.
 */
public final class XmlDocuments {

    /** Message of a document of an unsupported type: its class. */
    static final String NOT_A_DOCUMENT = "the XML document must be a String, a Reader, a javax.xml.transform.Source, "
            + "an org.w3c.dom.Node or a NodeList of one node, not %s";

    /** Message of a node list that is not a single node: its length. */
    static final String NOT_ONE_NODE = "the XML document must be a single node, not a NodeList of %d nodes";

    /** Message of a stylesheet of an unsupported type: its class. */
    static final String NOT_A_STYLESHEET =
            "the XSLT stylesheet must be a String, a Reader or a javax.xml.transform.Source, not %s";

    private XmlDocuments() {
    }

    /**
     * Parses a document for {@code x:parse} (Tags 3.0 section 11.2): without a filter with a
     * hardened {@code DocumentBuilder}; with one, by an identity transform of the filter's
     * events into a DOM tree, the filter reading from a hardened {@code XMLReader}.
     *
     * <p>The input carries {@link SystemIdPolicy#parserSystemId()}, which also becomes the
     * document URI when the page gave a system id; without one the document URI is
     * {@code null}, never the policy's private base. The filter, when given, gets that reader
     * as its parent and the policy as its own entity resolver: an {@code XMLFilterImpl}
     * replaces its parent's resolver by itself when it parses, so a resolver left on the parent
     * alone is never asked (probe: {@code JdkXmlFactsTest}). {@code input} is not closed.
     *
     * @param input  the document
     * @param policy the resolution of the external references the document makes
     * @param filter the page's filter, {@code null} for none
     * @return the parsed document
     * @throws SAXException         if the document is malformed or a reference is refused
     *                              (without filter)
     * @throws IOException          if the input cannot be read, or a refused reference
     *                              surfaces as a malformed URL
     * @throws TransformerException if the filtered document cannot be built (with filter)
     */
    public static Document parse(Reader input, SystemIdPolicy policy, XMLFilter filter)
            throws SAXException, IOException, TransformerException {
        InputSource source = new InputSource(input);
        String systemId = policy.parserSystemId();
        source.setSystemId(systemId);
        String documentUri = policy.systemId() == null ? null : systemId;
        if (filter == null) {
            Document document = XmlFactories.documentBuilder(policy).parse(source);
            document.setDocumentURI(documentUri);
            return document;
        }
        filter.setParent(XmlFactories.xmlReader(policy));
        filter.setEntityResolver(policy);
        TransformerFactory factory = XmlFactories.transformerFactory(policy);
        Transformer identity = factory.newTransformer();
        // A Transformer does not inherit the factory's ErrorListener.
        identity.setErrorListener(factory.getErrorListener());
        Document document = XmlFactories.emptyDocument();
        document.setDocumentURI(documentUri);
        identity.transform(new SAXSource(filter, source), new DOMResult(document, documentUri));
        return document;
    }

    /**
     * The document of {@code x:transform} (Tags 3.0 section 13.2). A {@code String} or a
     * {@code Reader} is read by a hardened {@code XMLReader} that resolves its external
     * references through {@code policy}, under {@link SystemIdPolicy#parserSystemId()}; a
     * {@code Source} is passed as is; a {@code Node} (the {@code Document} of
     * {@code x:parse}) and a {@code NodeList} of exactly one node (an {@code x:set}
     * node-set) become a {@code DOMSource} of that node. A {@code Node} is tested first, as
     * a DOM implementation's nodes may also implement {@code NodeList}. {@code doc} is not
     * closed.
     *
     * @param doc    the document, not {@code null}
     * @param policy the resolution of the external references the document makes
     * @return the source of the transformation
     * @throws IllegalArgumentException naming the type of {@code doc}, or the length of a
     *                                  {@code NodeList} that is not a single node
     */
    public static Source documentSource(Object doc, SystemIdPolicy policy) {
        if (doc instanceof String || doc instanceof Reader) {
            return parsed(doc, policy);
        }
        if (doc instanceof Source source) {
            return source;
        }
        if (doc instanceof Node node) {
            return new DOMSource(node);
        }
        if (doc instanceof NodeList nodes) {
            if (nodes.getLength() != 1) {
                throw new IllegalArgumentException(NOT_ONE_NODE.formatted(nodes.getLength()));
            }
            return new DOMSource(nodes.item(0));
        }
        throw new IllegalArgumentException(NOT_A_DOCUMENT.formatted(doc.getClass().getName()));
    }

    /**
     * The stylesheet of {@code x:transform} (Tags 3.0 section 13.2): a {@code String} or a
     * {@code Reader} read as by {@link #documentSource}, a {@code Source} as is. Its
     * {@code xsl:import}, {@code xsl:include} and {@code document()} references go to the
     * {@code URIResolver} of the factory and of the transformer, not to this reader.
     * {@code xslt} is not closed.
     *
     * @param xslt   the stylesheet, not {@code null}
     * @param policy the resolution of the external entities the stylesheet makes
     * @return the source of the stylesheet
     * @throws IllegalArgumentException naming the type of {@code xslt}
     */
    public static Source stylesheetSource(Object xslt, SystemIdPolicy policy) {
        if (xslt instanceof String || xslt instanceof Reader) {
            return parsed(xslt, policy);
        }
        if (xslt instanceof Source source) {
            return source;
        }
        throw new IllegalArgumentException(NOT_A_STYLESHEET.formatted(xslt.getClass().getName()));
    }

    /**
     * A {@code SAXSource} over a {@code String} or a {@code Reader}: the reader keeps its
     * entity resolver while the JDK transforms (probe: {@code JdkXmlFactsTest}).
     */
    private static SAXSource parsed(Object text, SystemIdPolicy policy) {
        InputSource input = new InputSource(text instanceof Reader reader ? reader : new StringReader((String) text));
        input.setSystemId(policy.parserSystemId());
        return new SAXSource(XmlFactories.xmlReader(policy), input);
    }
}
