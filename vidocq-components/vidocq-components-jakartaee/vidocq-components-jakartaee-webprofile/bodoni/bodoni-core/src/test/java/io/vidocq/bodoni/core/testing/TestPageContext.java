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

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.el.ELContext;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.BodyContent;

/**
 * A {@link PageContext} double with real attribute scopes: page attributes live here,
 * request, session and application attributes are delegated to the servlet doubles.
 * {@code pushBody}/{@code popBody} stack {@link TestBodyContent}s over the base
 * {@link TestJspWriter}. Everything not listed in the class description throws
 * {@link UnsupportedOperationException}.
 */
public class TestPageContext extends PageContext {

    private final TestHttpServletRequest request;
    private final TestHttpServletResponse response;
    private final TestServletContext servletContext;
    private final Map<String, Object> page = new LinkedHashMap<>();
    private final Deque<JspWriter> outs = new ArrayDeque<>();
    private final TestELContext elContext = new TestELContext();

    public TestPageContext(TestHttpServletRequest request, TestHttpServletResponse response,
            TestServletContext servletContext) {
        this.request = request;
        this.response = response;
        this.servletContext = servletContext;
        outs.push(new TestJspWriter());
    }

    /** A page context over fresh, default doubles. */
    public static TestPageContext create() {
        TestServletContext ctx = new TestServletContext();
        return new TestPageContext(new TestHttpServletRequest(ctx), new TestHttpServletResponse(), ctx);
    }

    // ---- scopes -------------------------------------------------------------

    @Override
    public void setAttribute(String name, Object value) {
        setAttribute(name, value, PAGE_SCOPE);
    }

    @Override
    public void setAttribute(String name, Object value, int scope) {
        requireName(name);
        if (value == null) {
            removeAttribute(name, scope);
            return;
        }
        switch (scope) {
            case PAGE_SCOPE -> page.put(name, value);
            case REQUEST_SCOPE -> request.setAttribute(name, value);
            case SESSION_SCOPE -> request.getSession().setAttribute(name, value);
            case APPLICATION_SCOPE -> servletContext.setAttribute(name, value);
            default -> throw badScope(scope);
        }
    }

    @Override
    public Object getAttribute(String name) {
        return getAttribute(name, PAGE_SCOPE);
    }

    @Override
    public Object getAttribute(String name, int scope) {
        requireName(name);
        return switch (scope) {
            case PAGE_SCOPE -> page.get(name);
            case REQUEST_SCOPE -> request.getAttribute(name);
            case SESSION_SCOPE -> request.getSession().getAttribute(name);
            case APPLICATION_SCOPE -> servletContext.getAttribute(name);
            default -> throw badScope(scope);
        };
    }

    @Override
    public Object findAttribute(String name) {
        for (int scope = PAGE_SCOPE; scope <= APPLICATION_SCOPE; scope++) {
            Object value = getAttribute(name, scope);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    public void removeAttribute(String name) {
        for (int scope = PAGE_SCOPE; scope <= APPLICATION_SCOPE; scope++) {
            removeAttribute(name, scope);
        }
    }

    @Override
    public void removeAttribute(String name, int scope) {
        requireName(name);
        switch (scope) {
            case PAGE_SCOPE -> page.remove(name);
            case REQUEST_SCOPE -> request.removeAttribute(name);
            case SESSION_SCOPE -> request.getSession().removeAttribute(name);
            case APPLICATION_SCOPE -> servletContext.removeAttribute(name);
            default -> throw badScope(scope);
        }
    }

    @Override
    public int getAttributesScope(String name) {
        for (int scope = PAGE_SCOPE; scope <= APPLICATION_SCOPE; scope++) {
            if (getAttribute(name, scope) != null) {
                return scope;
            }
        }
        return 0;
    }

    @Override
    public Enumeration<String> getAttributeNamesInScope(int scope) {
        return switch (scope) {
            case PAGE_SCOPE -> Collections.enumeration(page.keySet().stream().toList());
            case REQUEST_SCOPE -> request.getAttributeNames();
            case SESSION_SCOPE -> request.getSession().getAttributeNames();
            case APPLICATION_SCOPE -> servletContext.getAttributeNames();
            default -> throw badScope(scope);
        };
    }

    // ---- output -------------------------------------------------------------

    @Override
    public JspWriter getOut() {
        return outs.peek();
    }

    @Override
    public BodyContent pushBody() {
        TestBodyContent body = new TestBodyContent(outs.peek());
        outs.push(body);
        return body;
    }

    @Override
    public JspWriter pushBody(Writer writer) {
        throw nope();
    }

    @Override
    public JspWriter popBody() {
        if (outs.size() <= 1) {
            throw new IllegalStateException("no BodyContent to pop");
        }
        outs.pop();
        return outs.peek();
    }

    @Override
    public ELContext getELContext() {
        return elContext;
    }

    // ---- collaborators ------------------------------------------------------

    @Override
    public HttpSession getSession() {
        return request.getSession();
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    // ---- not needed ---------------------------------------------------------

    @Override
    public void initialize(Servlet servlet, ServletRequest request, ServletResponse response, String errorPageURL,
            boolean needsSession, int bufferSize, boolean autoFlush) throws IOException {
        throw nope();
    }

    @Override
    public void release() {
        throw nope();
    }

    @Override
    public Object getPage() {
        throw nope();
    }

    @Override
    public Exception getException() {
        throw nope();
    }

    @Override
    public ServletConfig getServletConfig() {
        throw nope();
    }

    @Override
    public void forward(String relativeUrlPath) {
        throw nope();
    }

    @Override
    public void include(String relativeUrlPath) {
        throw nope();
    }

    @Override
    public void include(String relativeUrlPath, boolean flush) {
        throw nope();
    }

    @Override
    public void handlePageException(Exception e) {
        throw nope();
    }

    @Override
    public void handlePageException(Throwable t) {
        throw nope();
    }

    private static void requireName(String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }
    }

    private static IllegalArgumentException badScope(int scope) {
        return new IllegalArgumentException("Invalid scope: " + scope);
    }

    private static UnsupportedOperationException nope() {
        return new UnsupportedOperationException("not needed by Bodoni tests");
    }
}
