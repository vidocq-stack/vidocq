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

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.sax.SAXSource;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;

/**
 * The XML documents of the {@code x:} library, read with the hardened JAXP objects of
 * {@link XmlFactories} and the external references resolved by a {@link SystemIdPolicy}.
 */
public final class XmlDocuments {

    private XmlDocuments() {
    }

    /**
     * Parses a document for {@code x:parse} (Tags 3.0 section 11.2): without a filter with a
     * hardened {@code DocumentBuilder}; with one, by an identity transform of the filter's
     * events into a DOM tree, the filter reading from a hardened {@code XMLReader}.
     *
     * <p>The input carries {@link SystemIdPolicy#parserSystemId()}, which also becomes the
     * document URI when the page gave a system id; without one the document URI is
     * {@code null}, never the policy's private base. The filter, when given, gets that reader as its parent and the policy as
     * its own entity resolver: an {@code XMLFilterImpl} replaces its parent's resolver by
     * itself when it parses, so a resolver left on the parent alone is never asked (probe:
     * {@code JdkXmlFactsTest}). {@code input} is not closed.
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
}
