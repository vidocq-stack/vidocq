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

package io.vidocq.bodoni.core.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.el.ELContext;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.BodyContent;

import org.junit.jupiter.api.Test;

class TestPageContextTest {

    public static final class Bean {
        private String name = "n";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Test
    void scopesAreIndependentAndFindAttributeSearchesInOrder() {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("a", "app", PageContext.APPLICATION_SCOPE);
        assertEquals("app", pc.findAttribute("a"));
        pc.setAttribute("a", "ses", PageContext.SESSION_SCOPE);
        assertEquals("ses", pc.findAttribute("a"));
        pc.setAttribute("a", "req", PageContext.REQUEST_SCOPE);
        assertEquals("req", pc.findAttribute("a"));
        pc.setAttribute("a", "pg");
        assertEquals("pg", pc.findAttribute("a"));
        assertEquals(PageContext.PAGE_SCOPE, pc.getAttributesScope("a"));
        assertEquals("app", pc.getAttribute("a", PageContext.APPLICATION_SCOPE));
        assertEquals("app", pc.getServletContext().getAttribute("a"));
        assertEquals("ses", pc.getSession().getAttribute("a"));
        assertEquals("req", pc.getRequest().getAttribute("a"));
    }

    @Test
    void removeAttributeWithoutScopeClearsAllScopes() {
        TestPageContext pc = TestPageContext.create();
        for (int s = 1; s <= 4; s++) {
            pc.setAttribute("a", "v" + s, s);
        }
        pc.removeAttribute("a");
        assertNull(pc.findAttribute("a"));
        assertEquals(0, pc.getAttributesScope("a"));
    }

    @Test
    void removeAttributeInOneScopeKeepsTheOthers() {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("a", "p");
        pc.setAttribute("a", "r", PageContext.REQUEST_SCOPE);
        pc.removeAttribute("a", PageContext.PAGE_SCOPE);
        assertEquals("r", pc.findAttribute("a"));
    }

    @Test
    void nullValueRemoves() {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("a", "p");
        pc.setAttribute("a", null);
        assertNull(pc.findAttribute("a"));
    }

    @Test
    void attributeNamesInScope() {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("x", 1);
        pc.setAttribute("y", 2, PageContext.REQUEST_SCOPE);
        List<String> names = new ArrayList<>(java.util.Collections.list(pc.getAttributeNamesInScope(PageContext.PAGE_SCOPE)));
        assertEquals(List.of("x"), names);
    }

    @Test
    void pushAndPopBodyStackWriters() throws Exception {
        TestPageContext pc = TestPageContext.create();
        JspWriter base = pc.getOut();
        base.print("a");
        BodyContent one = pc.pushBody();
        assertSame(one, pc.getOut());
        pc.getOut().print("b");
        BodyContent two = pc.pushBody();
        pc.getOut().print("c");
        assertSame(one, two.getEnclosingWriter());
        assertEquals("c", two.getString());
        assertSame(one, pc.popBody());
        assertEquals("b", one.getString());
        assertSame(base, pc.popBody());
        assertEquals("a", ((TestJspWriter) base).getString());
    }

    @Test
    void elContextResolvesMapsListsArraysAndBeans() {
        TestPageContext pc = TestPageContext.create();
        ELContext el = pc.getELContext();
        Map<String, Object> map = new HashMap<>();
        map.put("k", "v");
        assertEquals("v", el.getELResolver().getValue(el, map, "k"));
        assertTrue(el.isPropertyResolved());
        el.setPropertyResolved(false);
        assertEquals("b", el.getELResolver().getValue(el, List.of("a", "b"), 1));
        el.setPropertyResolved(false);
        assertEquals("y", el.getELResolver().getValue(el, new String[] {"x", "y"}, 1));
        el.setPropertyResolved(false);
        Bean bean = new Bean();
        assertEquals("n", el.getELResolver().getValue(el, bean, "name"));
        el.setPropertyResolved(false);
        el.getELResolver().setValue(el, bean, "name", "m");
        assertEquals("m", bean.getName());
        assertNull(el.getFunctionMapper().resolveFunction("a", "b"));
    }

    @Test
    void responseAndRequestDoublesRecordWhatTagsDo() throws Exception {
        TestPageContext pc = TestPageContext.create();
        TestHttpServletResponse resp = (TestHttpServletResponse) pc.getResponse();
        TestHttpServletRequest req = (TestHttpServletRequest) pc.getRequest();
        assertEquals("ISO-8859-1", resp.getCharacterEncoding());
        assertEquals("/app", req.getContextPath());
        assertEquals("/p;jsessionid=T", resp.encodeURL("/p"));
        assertEquals("/q;jsessionid=T", resp.encodeRedirectURL("/q"));
        assertEquals(List.of("/p"), resp.encodedUrls());
        assertEquals(List.of("/q"), resp.encodedRedirectUrls());
        resp.sendRedirect("/go");
        assertEquals("/go", resp.redirect());
        req.dispatchers().content("/inc", "hello");
        req.dispatchers().status("/st", 404);
        req.dispatchers().failWith("/bad", new java.io.IOException("io"));
        resp.getWriter();
        req.getRequestDispatcher("/inc").include(req, resp);
        assertEquals("hello", resp.body());
        req.getRequestDispatcher("/st").include(req, resp);
        assertEquals(404, resp.getStatus());
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> req.getRequestDispatcher("/bad").include(req, resp));
    }
}
