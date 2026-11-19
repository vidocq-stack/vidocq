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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.XMLConstants;

import org.junit.jupiter.api.Test;

class XPathScopesTest {

    private static final List<String> SCOPES = List.of("param", "header", "cookie", "initParam", "pageScope",
            "requestScope", "sessionScope", "applicationScope");

    private final XPathScopes scopes = new XPathScopes();

    @Test
    void bindsTheSection1112PrefixesToBodoniNamespaces() {
        for (String prefix : SCOPES) {
            String uri = "https://vidocq.dev/ns/bodoni/xpath/" + prefix;
            assertEquals(uri, scopes.getNamespaceURI(prefix));
            assertEquals(prefix, scopes.getPrefix(uri));
            assertEquals(List.of(prefix), list(scopes.getPrefixes(uri)));
        }
    }

    @Test
    void reservedPrefixesFollowTheNamespaceContextContract() {
        assertEquals(XMLConstants.XML_NS_URI, scopes.getNamespaceURI("xml"));
        assertEquals("xml", scopes.getPrefix(XMLConstants.XML_NS_URI));
        assertEquals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, scopes.getNamespaceURI("xmlns"));
        assertEquals("xmlns", scopes.getPrefix(XMLConstants.XMLNS_ATTRIBUTE_NS_URI));
    }

    @Test
    void anyOtherPrefixIsUnbound() {
        assertEquals(XMLConstants.NULL_NS_URI, scopes.getNamespaceURI("foo"));
        assertEquals(XMLConstants.NULL_NS_URI, scopes.getNamespaceURI(""));
        assertNull(scopes.getPrefix("urn:other"));
        assertFalse(scopes.getPrefixes("urn:other").hasNext());
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> scopes.getNamespaceURI(null));
        assertThrows(IllegalArgumentException.class, () -> scopes.getPrefix(null));
        assertThrows(IllegalArgumentException.class, () -> scopes.getPrefixes(null));
    }

    private static List<String> list(Iterator<String> prefixes) {
        List<String> out = new ArrayList<>();
        prefixes.forEachRemaining(out::add);
        return out;
    }
}
