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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import javax.xml.transform.TransformerException;

import io.vidocq.bodoni.core.testing.AttributeAddingFilter;
import io.vidocq.bodoni.core.testing.TestPageContext;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** {@link XmlDocuments#parse}: the {@code x:parse} paths without a tag (Tags 3.0 section 11.2). */
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

    @Test
    void theFilterResolvesExternalReferencesThroughThePolicy() {
        SystemIdPolicy policy = policy(null);
        AttributeAddingFilter filter = new AttributeAddingFilter();
        StringReader input = new StringReader(EXTERNAL);

        assertThrows(TransformerException.class, () -> XmlDocuments.parse(input, policy, filter));

        // An XMLFilterImpl only has the two-argument resolveEntity: the parser hands it the
        // reference already expanded against the JVM working directory.
        String message = policy.recordedFailure().getMessage();
        assertTrue(message.startsWith("x:parse: external reference 'file:"), message);
        assertTrue(message.endsWith("/e.xml' refused, system id '(none)': no system id"), message);
    }

    @Test
    void withoutAFilterExternalReferencesGoThroughThePolicy() {
        SystemIdPolicy policy = policy(null);
        StringReader input = new StringReader(EXTERNAL);

        assertThrows(SAXException.class, () -> XmlDocuments.parse(input, policy, null));

        assertEquals(REFUSED, policy.recordedFailure().getMessage());
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
}
