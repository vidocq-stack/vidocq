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

import java.io.BufferedReader;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

/**
 * An {@link HttpServletRequest} double: attributes, parameters, character encoding, a
 * context path (default {@code /app}), an always-present session and request dispatchers
 * configured through {@link #dispatchers()}. Everything else throws
 * {@link UnsupportedOperationException}.
 */
public class TestHttpServletRequest implements HttpServletRequest {

    private final TestServletContext context;
    private final TestHttpSession session;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final Map<String, String[]> parameters = new LinkedHashMap<>();
    private final TestDispatchers dispatchers = new TestDispatchers();
    private String contextPath = "/app";
    private String characterEncoding;

    public TestHttpServletRequest(TestServletContext context) {
        this.context = context;
        this.session = new TestHttpSession(context);
    }

    /** Changes the context path. */
    public TestHttpServletRequest contextPath(String path) {
        this.contextPath = path;
        return this;
    }

    /** Sets (replaces) a request parameter. */
    public TestHttpServletRequest parameter(String name, String... values) {
        parameters.put(name, values);
        return this;
    }

    /** Dispatcher behaviours served by {@code getRequestDispatcher(path)}. */
    public TestDispatchers dispatchers() {
        return dispatchers;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return dispatchers.get(path);
    }

    @Override
    public ServletContext getServletContext() {
        return context;
    }

    @Override
    public HttpSession getSession() {
        return session;
    }

    @Override
    public HttpSession getSession(boolean create) {
        return session;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet().stream().toList());
    }

    @Override
    public void setAttribute(String name, Object o) {
        if (o == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, o);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String env) {
        this.characterEncoding = env;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameters.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters.keySet().stream().toList());
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = parameters.get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return Collections.unmodifiableMap(parameters);
    }

    @Override
    public String getAuthType() {
        throw nope();
    }

    @Override
    public Cookie[] getCookies() {
        throw nope();
    }

    @Override
    public long getDateHeader(String name) {
        throw nope();
    }

    @Override
    public String getHeader(String name) {
        throw nope();
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        throw nope();
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        throw nope();
    }

    @Override
    public int getIntHeader(String name) {
        throw nope();
    }

    @Override
    public String getMethod() {
        throw nope();
    }

    @Override
    public String getPathInfo() {
        throw nope();
    }

    @Override
    public String getPathTranslated() {
        throw nope();
    }

    @Override
    public String getQueryString() {
        throw nope();
    }

    @Override
    public String getRemoteUser() {
        throw nope();
    }

    @Override
    public boolean isUserInRole(String role) {
        throw nope();
    }

    @Override
    public Principal getUserPrincipal() {
        throw nope();
    }

    @Override
    public String getRequestedSessionId() {
        throw nope();
    }

    @Override
    public String getRequestURI() {
        throw nope();
    }

    @Override
    public StringBuffer getRequestURL() {
        throw nope();
    }

    @Override
    public String getServletPath() {
        throw nope();
    }

    @Override
    public String changeSessionId() {
        throw nope();
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        throw nope();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        throw nope();
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        throw nope();
    }

    @Override
    public boolean authenticate(HttpServletResponse response) {
        throw nope();
    }

    @Override
    public void login(String username, String password) {
        throw nope();
    }

    @Override
    public void logout() {
        throw nope();
    }

    @Override
    public Collection<Part> getParts() {
        throw nope();
    }

    @Override
    public Part getPart(String name) {
        throw nope();
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw nope();
    }

    @Override
    public int getContentLength() {
        throw nope();
    }

    @Override
    public long getContentLengthLong() {
        throw nope();
    }

    @Override
    public String getContentType() {
        throw nope();
    }

    @Override
    public ServletInputStream getInputStream() {
        throw nope();
    }

    @Override
    public String getProtocol() {
        throw nope();
    }

    @Override
    public String getScheme() {
        throw nope();
    }

    @Override
    public String getServerName() {
        throw nope();
    }

    @Override
    public int getServerPort() {
        throw nope();
    }

    @Override
    public BufferedReader getReader() {
        throw nope();
    }

    @Override
    public String getRemoteAddr() {
        throw nope();
    }

    @Override
    public String getRemoteHost() {
        throw nope();
    }

    @Override
    public Locale getLocale() {
        throw nope();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        throw nope();
    }

    @Override
    public boolean isSecure() {
        throw nope();
    }

    @Override
    public int getRemotePort() {
        throw nope();
    }

    @Override
    public String getLocalName() {
        throw nope();
    }

    @Override
    public String getLocalAddr() {
        throw nope();
    }

    @Override
    public int getLocalPort() {
        throw nope();
    }

    @Override
    public AsyncContext startAsync() {
        throw nope();
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        throw nope();
    }

    @Override
    public boolean isAsyncStarted() {
        throw nope();
    }

    @Override
    public boolean isAsyncSupported() {
        throw nope();
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw nope();
    }

    @Override
    public DispatcherType getDispatcherType() {
        throw nope();
    }

    @Override
    public String getRequestId() {
        throw nope();
    }

    @Override
    public String getProtocolRequestId() {
        throw nope();
    }

    @Override
    public ServletConnection getServletConnection() {
        throw nope();
    }

    private static UnsupportedOperationException nope() {
        return new UnsupportedOperationException("not needed by Bodoni tests");
    }
}
