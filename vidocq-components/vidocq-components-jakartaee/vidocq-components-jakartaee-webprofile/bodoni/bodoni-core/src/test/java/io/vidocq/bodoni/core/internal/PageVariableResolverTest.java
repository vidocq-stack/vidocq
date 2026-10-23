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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;

import javax.xml.namespace.QName;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

/** The section 11.1.2 variable bindings and the section 11.1.3 Java to XPath mapping. */
class PageVariableResolverTest {

    private static final String NS = "https://vidocq.dev/ns/bodoni/xpath/";

    private final TestServletContext context = new TestServletContext();
    private final TestHttpServletRequest request = new TestHttpServletRequest(context);
    private final TestPageContext pc = new TestPageContext(request, new TestHttpServletResponse(), context);
    private final PageVariableResolver resolver = new PageVariableResolver(pc);

    @Test
    void theTckBindingsResolveThroughXPath() throws Exception {
        pc.setAttribute("findVar", "foundit", PageContext.SESSION_SCOPE);
        pc.setAttribute("objPage", "page", PageContext.PAGE_SCOPE);
        pc.setAttribute("objRequest", "req", PageContext.REQUEST_SCOPE);
        pc.setAttribute("objSession", "sess", PageContext.SESSION_SCOPE);
        pc.setAttribute("objApplication", "appl", PageContext.APPLICATION_SCOPE);
        context.initParameter("initBinding", "initBinding");
        request.parameter("param1", "RequestParameter1");
        request.cookies(new Cookie("mycookie", "CookieFound"));
        request.header("h", "header value");

        assertEquals("foundit", string("string($findVar)"));
        assertEquals("page", string("string($pageScope:objPage)"));
        assertEquals("req", string("string($requestScope:objRequest)"));
        assertEquals("sess", string("string($sessionScope:objSession)"));
        assertEquals("appl", string("string($applicationScope:objApplication)"));
        assertEquals("initBinding", string("string($initParam:initBinding)"));
        assertEquals("RequestParameter1", string("string($param:param1)"));
        assertEquals("CookieFound", string("string($cookie:mycookie)"));
        assertEquals("header value", string("string($header:h)"));
    }

    @Test
    void scopedPrefixesReadOneScopeOnly() {
        pc.setAttribute("x", "request", PageContext.REQUEST_SCOPE);

        assertNull(resolver.resolveVariable(scoped("pageScope", "x")));
        assertEquals("request", resolver.resolveVariable(scoped("requestScope", "x")));
        assertNull(resolver.resolveVariable(scoped("sessionScope", "x")));
        assertNull(resolver.resolveVariable(scoped("applicationScope", "x")));
        assertEquals("request", resolver.resolveVariable(new QName("x")));
    }

    @Test
    void cookieIsTheFirstOfThatName() {
        request.cookies(new Cookie("other", "no"), new Cookie("c", "first"), new Cookie("c", "second"));

        assertEquals("first", resolver.resolveVariable(scoped("cookie", "c")));
        assertNull(resolver.resolveVariable(scoped("cookie", "absent")));
    }

    @Test
    void noCookieAndNoHeaderAreUnbound() {
        assertNull(resolver.resolveVariable(scoped("cookie", "c")));
        assertNull(resolver.resolveVariable(scoped("header", "h")));
    }

    @Test
    void sessionScopeWithoutASessionIsUnboundAndCreatesNone() {
        request.withoutSession();

        assertNull(resolver.resolveVariable(scoped("sessionScope", "x")));
        assertFalse(request.hasSession());
    }

    @Test
    void sessionScopeOfAnInvalidatedSessionIsUnbound() {
        pc.setAttribute("x", "sess", PageContext.SESSION_SCOPE);
        pc.getSession().invalidate();

        assertNull(resolver.resolveVariable(scoped("sessionScope", "x")));
    }

    @Test
    void otherNamespacesAreUnbound() {
        pc.setAttribute("x", "page");

        assertNull(resolver.resolveVariable(new QName("urn:other", "x")));
    }

    @Test
    void nullNameIsRefused() {
        assertThrows(NullPointerException.class, () -> resolver.resolveVariable(null));
    }

    @Test
    void numbersBecomeDoubles() {
        assertEquals(1.0, toXPath(1));
        assertEquals(3.0, toXPath(3L));
        assertEquals(2.5, toXPath(new BigDecimal("2.5")));
        assertEquals(0.5, toXPath(0.5f));
    }

    @Test
    void booleansStringsAndDomObjectsAreKept() {
        Document document = XmlFactories.emptyDocument();
        Element element = document.createElement("e");
        NodeSet nodes = NodeSet.of(List.of(element));

        assertSame(Boolean.FALSE, toXPath(Boolean.FALSE));
        assertSame("name", toXPath("name"));
        assertSame(document, toXPath(document));
        assertSame(element, toXPath(element));
        assertSame(nodes, toXPath(nodes));
    }

    @Test
    void otherObjectsBecomeTheirString() {
        assertEquals("sb", toXPath(new StringBuilder("sb")));
        assertEquals("[a, b]", toXPath(List.of("a", "b")));
    }

    private Object toXPath(Object value) {
        pc.setAttribute("v", value);
        return resolver.resolveVariable(new QName("v"));
    }

    private String string(String select) throws Exception {
        return XPathEngine.string(pc, null, select);
    }

    private static QName scoped(String prefix, String name) {
        return new QName(NS + prefix, name);
    }
}
