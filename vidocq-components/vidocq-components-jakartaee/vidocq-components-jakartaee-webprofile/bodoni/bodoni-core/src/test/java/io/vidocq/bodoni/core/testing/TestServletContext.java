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

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;

/**
 * A {@link ServletContext} double: application attributes, a context path (default
 * {@code /app}), foreign contexts for {@link #getContext(String)} and request dispatchers
 * configured through {@link #dispatchers()}. Everything else throws
 * {@link UnsupportedOperationException}.
 */
public class TestServletContext implements ServletContext {

    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final Map<String, TestServletContext> foreign = new LinkedHashMap<>();
    private final TestDispatchers dispatchers = new TestDispatchers();
    private String contextPath = "/app";

    /** Changes the context path. */
    public TestServletContext contextPath(String path) {
        this.contextPath = path;
        return this;
    }

    /** Registers a foreign context, returned by {@code getContext(path)}. */
    public TestServletContext addContext(String path, TestServletContext other) {
        foreign.put(path, other);
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
    public ServletContext getContext(String uripath) {
        return foreign.get(uripath);
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return dispatchers.get(path);
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
    public void setAttribute(String name, Object object) {
        if (object == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, object);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public int getMajorVersion() {
        throw nope();
    }

    @Override
    public int getMinorVersion() {
        throw nope();
    }

    @Override
    public int getEffectiveMajorVersion() {
        throw nope();
    }

    @Override
    public int getEffectiveMinorVersion() {
        throw nope();
    }

    @Override
    public String getMimeType(String file) {
        throw nope();
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        throw nope();
    }

    @Override
    public URL getResource(String path) {
        throw nope();
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        throw nope();
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        throw nope();
    }

    @Override
    public void log(String msg) {
        throw nope();
    }

    @Override
    public void log(String message, Throwable throwable) {
        throw nope();
    }

    @Override
    public String getRealPath(String path) {
        throw nope();
    }

    @Override
    public String getServerInfo() {
        throw nope();
    }

    @Override
    public String getInitParameter(String name) {
        throw nope();
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        throw nope();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        throw nope();
    }

    @Override
    public String getServletContextName() {
        throw nope();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        throw nope();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        throw nope();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        throw nope();
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        throw nope();
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) {
        throw nope();
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        throw nope();
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        throw nope();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw nope();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        throw nope();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        throw nope();
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) {
        throw nope();
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        throw nope();
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        throw nope();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        throw nope();
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        throw nope();
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        throw nope();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        throw nope();
    }

    @Override
    public void addListener(String className) {
        throw nope();
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
        throw nope();
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        throw nope();
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) {
        throw nope();
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        throw nope();
    }

    @Override
    public ClassLoader getClassLoader() {
        throw nope();
    }

    @Override
    public void declareRoles(String... roleNames) {
        throw nope();
    }

    @Override
    public String getVirtualServerName() {
        throw nope();
    }

    @Override
    public int getSessionTimeout() {
        throw nope();
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        throw nope();
    }

    @Override
    public String getRequestCharacterEncoding() {
        throw nope();
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {
        throw nope();
    }

    @Override
    public String getResponseCharacterEncoding() {
        throw nope();
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {
        throw nope();
    }

    private static UnsupportedOperationException nope() {
        return new UnsupportedOperationException("not needed by Bodoni tests");
    }
}
