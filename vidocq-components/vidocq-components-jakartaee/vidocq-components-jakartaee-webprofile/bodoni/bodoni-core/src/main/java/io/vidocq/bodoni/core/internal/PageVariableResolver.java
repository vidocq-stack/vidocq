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

import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.xpath.XPathVariableResolver;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.PageContext;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Resolves the XPath variables of Tags 3.0 section 11.1.2 against a page: {@code $foo} is
 * {@code findAttribute("foo")}, the {@link XPathScopes} prefixes read the request
 * parameters, headers, cookies, context init parameters and the four attribute scopes.
 *
 * <p>An absent value resolves to {@code null}, which the JDK reports as an evaluation
 * failure: "a variable name that is not bound to any value results in an exception"
 * (section 11.1.3). {@code $sessionScope:foo} never creates a session: without one, or with
 * an invalidated one, the variable is unbound. A value is handed to the JDK per the Java to
 * XPath mapping of section 11.1.3: {@code Boolean}, {@code String}, DOM {@link Node} and
 * {@link NodeList} as is, any {@code Number} as a {@code Double}, any other object as its
 * {@code String.valueOf}.
 */
public final class PageVariableResolver implements XPathVariableResolver {

    private final PageContext pc;

    /**
     * A resolver over {@code pc}.
     *
     * @param pc the page whose scopes the variables read
     */
    public PageVariableResolver(PageContext pc) {
        this.pc = pc;
    }

    @Override
    public Object resolveVariable(QName variableName) {
        Objects.requireNonNull(variableName, "variableName");
        Object value = lookUp(variableName.getNamespaceURI(), variableName.getLocalPart());
        return value == null ? null : toXPath(value);
    }

    private Object lookUp(String namespace, String name) {
        return switch (namespace) {
            case XMLConstants.NULL_NS_URI -> pc.findAttribute(name);
            case XPathScopes.PARAM -> pc.getRequest().getParameter(name);
            case XPathScopes.HEADER -> header(name);
            case XPathScopes.COOKIE -> cookie(name);
            case XPathScopes.INIT_PARAM -> pc.getServletContext().getInitParameter(name);
            case XPathScopes.PAGE_SCOPE -> pc.getAttribute(name, PageContext.PAGE_SCOPE);
            case XPathScopes.REQUEST_SCOPE -> pc.getAttribute(name, PageContext.REQUEST_SCOPE);
            case XPathScopes.SESSION_SCOPE -> sessionAttribute(name);
            case XPathScopes.APPLICATION_SCOPE -> pc.getAttribute(name, PageContext.APPLICATION_SCOPE);
            default -> null;
        };
    }

    private HttpServletRequest http() {
        return pc.getRequest() instanceof HttpServletRequest http ? http : null;
    }

    private String header(String name) {
        HttpServletRequest http = http();
        return http == null ? null : http.getHeader(name);
    }

    private String cookie(String name) {
        HttpServletRequest http = http();
        Cookie[] cookies = http == null ? null : http.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private Object sessionAttribute(String name) {
        HttpServletRequest http = http();
        HttpSession session = http == null ? null : http.getSession(false);
        if (session == null) {
            return null;
        }
        try {
            return session.getAttribute(name);
        } catch (IllegalStateException _) {
            // invalidated session: behave as if there were none
            return null;
        }
    }

    private static Object toXPath(Object value) {
        return switch (value) {
            case Boolean _, String _, Node _, NodeList _ -> value;
            case Number number -> number.doubleValue();
            default -> String.valueOf(value);
        };
    }
}
