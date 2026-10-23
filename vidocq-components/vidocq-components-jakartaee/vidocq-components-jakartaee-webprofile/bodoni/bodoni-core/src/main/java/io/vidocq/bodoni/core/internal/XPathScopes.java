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

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;

/**
 * The namespace prefixes of the {@code x:} XPath expressions: the scope prefixes of
 * Tags 3.0 section 11.1.2 ({@code $param:foo}, {@code $sessionScope:foo}, ...), each bound
 * to a Bodoni namespace that {@link PageVariableResolver} recognises, plus the reserved
 * {@code xml} and {@code xmlns} prefixes of the {@link NamespaceContext} contract. Any
 * other prefix is unbound ({@link XMLConstants#NULL_NS_URI}). Stateless and cheap: each
 * {@link XPathEngine} evaluator gets its own.
 */
public final class XPathScopes implements NamespaceContext {

    /** The namespace of the scope prefix {@code p} is this base followed by {@code p}. */
    static final String BASE = "https://vidocq.dev/ns/bodoni/xpath/";

    static final String PARAM = BASE + "param";
    static final String HEADER = BASE + "header";
    static final String COOKIE = BASE + "cookie";
    static final String INIT_PARAM = BASE + "initParam";
    static final String PAGE_SCOPE = BASE + "pageScope";
    static final String REQUEST_SCOPE = BASE + "requestScope";
    static final String SESSION_SCOPE = BASE + "sessionScope";
    static final String APPLICATION_SCOPE = BASE + "applicationScope";

    private static final Map<String, String> URIS = Map.of(
            "param", PARAM,
            "header", HEADER,
            "cookie", COOKIE,
            "initParam", INIT_PARAM,
            "pageScope", PAGE_SCOPE,
            "requestScope", REQUEST_SCOPE,
            "sessionScope", SESSION_SCOPE,
            "applicationScope", APPLICATION_SCOPE,
            XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI,
            XMLConstants.XMLNS_ATTRIBUTE, XMLConstants.XMLNS_ATTRIBUTE_NS_URI);

    /** The bindings above. */
    XPathScopes() {
        // Stateless: the bindings are the shared constant table.
    }

    @Override
    public String getNamespaceURI(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("null prefix");
        }
        return URIS.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
    }

    @Override
    public String getPrefix(String namespaceURI) {
        Iterator<String> prefixes = getPrefixes(namespaceURI);
        return prefixes.hasNext() ? prefixes.next() : null;
    }

    @Override
    public Iterator<String> getPrefixes(String namespaceURI) {
        if (namespaceURI == null) {
            throw new IllegalArgumentException("null namespace URI");
        }
        List<String> prefixes = URIS.entrySet().stream()
                .filter(binding -> binding.getValue().equals(namespaceURI))
                .map(Map.Entry::getKey)
                .toList();
        return prefixes.iterator();
    }
}
