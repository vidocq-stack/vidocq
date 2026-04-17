package fr.vidocq.vidocq.ext.servlet.chappe.container;

import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.DispatchResolver;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.RequestDispatcherImpl;
import fr.vidocq.vidocq.ext.servlet.chappe.listener.ListenerRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link ServletContext} minimal pour le jalon M2a.
 * <p>
 * Les opérations de configuration dynamique (addServlet/addFilter/...) ne sont pas
 * supportées dans ce jalon — on s'appuie uniquement sur la découverte CDI des
 * beans annotés {@code @WebServlet}.
 * </p>
 */
public final class VidocqServletContext implements ServletContext {

    private final String contextPath;
    private final String serverInfo;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, String> initParameters = new HashMap<>();
    private String requestCharacterEncoding = "UTF-8";
    private String responseCharacterEncoding = "UTF-8";
    private int sessionTimeout = 30;
    private ListenerRegistry listenerRegistry = new ListenerRegistry();
    private DispatchResolver dispatchResolver;
    private RequestDispatcherImpl.Invoker dispatchInvoker;
    private fr.vidocq.vidocq.ext.servlet.chappe.error.ErrorPageRegistry errorPages =
            new fr.vidocq.vidocq.ext.servlet.chappe.error.ErrorPageRegistry();
    private fr.vidocq.vidocq.ext.servlet.chappe.security.SecurityProvider securityProvider =
            new fr.vidocq.vidocq.ext.servlet.chappe.security.AnonymousSecurityProvider();

    public VidocqServletContext(String contextPath) {
        this.contextPath = contextPath;
        this.serverInfo = "Vidocq Servlet/Chappe";
    }

    public void setListenerRegistry(ListenerRegistry registry) {
        this.listenerRegistry = registry;
    }

    public ListenerRegistry listenerRegistry() { return listenerRegistry; }

    public void setDispatchInfrastructure(DispatchResolver resolver, RequestDispatcherImpl.Invoker invoker) {
        this.dispatchResolver = resolver;
        this.dispatchInvoker = invoker;
    }

    public fr.vidocq.vidocq.ext.servlet.chappe.error.ErrorPageRegistry errorPages() {
        return errorPages;
    }

    public void setErrorPages(fr.vidocq.vidocq.ext.servlet.chappe.error.ErrorPageRegistry errorPages) {
        this.errorPages = errorPages;
    }

    public fr.vidocq.vidocq.ext.servlet.chappe.security.SecurityProvider securityProvider() {
        return securityProvider;
    }

    public void setSecurityProvider(fr.vidocq.vidocq.ext.servlet.chappe.security.SecurityProvider provider) {
        this.securityProvider = java.util.Objects.requireNonNull(provider);
    }

    @Override public String getContextPath() { return contextPath; }
    @Override public ServletContext getContext(String uripath) { return null; }
    @Override public int getMajorVersion() { return 6; }
    @Override public int getMinorVersion() { return 1; }
    @Override public int getEffectiveMajorVersion() { return 6; }
    @Override public int getEffectiveMinorVersion() { return 1; }
    @Override public String getMimeType(String file) { return null; }
    @Override public Set<String> getResourcePaths(String path) { return Set.of(); }
    @Override public java.net.URL getResource(String path) { return null; }
    @Override public java.io.InputStream getResourceAsStream(String path) { return null; }
    @Override public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null || dispatchResolver == null || dispatchInvoker == null) return null;
        String resolvePath = path;
        if (!contextPath.equals("/") && path.startsWith(contextPath)) {
            resolvePath = path.substring(contextPath.length());
            if (resolvePath.isEmpty()) resolvePath = "/";
        }
        return dispatchResolver.resolve(resolvePath)
                .<RequestDispatcher>map(t -> new RequestDispatcherImpl(t, dispatchInvoker))
                .orElse(null);
    }
    @Override public RequestDispatcher getNamedDispatcher(String name) { return null; }
    @Override public void log(String msg) { System.getLogger("servlet.log").log(System.Logger.Level.INFO, msg); }
    @Override public void log(String message, Throwable throwable) {
        System.getLogger("servlet.log").log(System.Logger.Level.ERROR, message, throwable);
    }
    @Override public String getRealPath(String path) { return null; }
    @Override public String getServerInfo() { return serverInfo; }
    @Override public String getInitParameter(String name) { return initParameters.get(name); }
    @Override public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
    @Override public boolean setInitParameter(String name, String value) {
        return initParameters.putIfAbsent(name, value) == null;
    }

    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
    @Override public void setAttribute(String name, Object object) {
        if (object == null) { removeAttribute(name); return; }
        Object previous = attributes.put(name, object);
        if (previous == null) listenerRegistry.fireContextAttributeAdded(this, name, object);
        else listenerRegistry.fireContextAttributeReplaced(this, name, previous);
    }
    @Override public void removeAttribute(String name) {
        Object previous = attributes.remove(name);
        if (previous != null) listenerRegistry.fireContextAttributeRemoved(this, name, previous);
    }

    @Override public String getServletContextName() { return "vidocq"; }

    // ---- Dynamic registration — not supported in M2a ----

    @Override public ServletRegistration.Dynamic addServlet(String s, String s1) { throw unsupported(); }
    @Override public ServletRegistration.Dynamic addServlet(String s, Servlet servlet) { throw unsupported(); }
    @Override public ServletRegistration.Dynamic addServlet(String s, Class<? extends Servlet> c) { throw unsupported(); }
    @Override public ServletRegistration.Dynamic addJspFile(String s, String s1) { throw unsupported(); }
    @Override public <T extends Servlet> T createServlet(Class<T> c) { throw unsupported(); }
    @Override public ServletRegistration getServletRegistration(String name) { return null; }
    @Override public Map<String, ? extends ServletRegistration> getServletRegistrations() { return Map.of(); }
    @Override public FilterRegistration.Dynamic addFilter(String s, String s1) { throw unsupported(); }
    @Override public FilterRegistration.Dynamic addFilter(String s, Filter f) { throw unsupported(); }
    @Override public FilterRegistration.Dynamic addFilter(String s, Class<? extends Filter> c) { throw unsupported(); }
    @Override public <T extends Filter> T createFilter(Class<T> c) { throw unsupported(); }
    @Override public FilterRegistration getFilterRegistration(String name) { return null; }
    @Override public Map<String, ? extends FilterRegistration> getFilterRegistrations() { return Map.of(); }

    // ---- Listeners — not supported in M2a ----

    @Override public void addListener(String className) { throw unsupported(); }
    @Override public <T extends java.util.EventListener> void addListener(T t) { throw unsupported(); }
    @Override public void addListener(Class<? extends java.util.EventListener> listenerClass) { throw unsupported(); }
    @Override public <T extends java.util.EventListener> T createListener(Class<T> c) { throw unsupported(); }

    // ---- Sessions — stubs ----

    @Override public SessionCookieConfig getSessionCookieConfig() { throw unsupported(); }
    @Override public void setSessionTrackingModes(Set<SessionTrackingMode> modes) { throw unsupported(); }
    @Override public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return EnumSet.of(SessionTrackingMode.COOKIE);
    }
    @Override public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return EnumSet.of(SessionTrackingMode.COOKIE);
    }
    @Override public int getSessionTimeout() { return sessionTimeout; }
    @Override public void setSessionTimeout(int sessionTimeout) { this.sessionTimeout = sessionTimeout; }

    @Override public JspConfigDescriptor getJspConfigDescriptor() { return null; }
    @Override public ClassLoader getClassLoader() { return Thread.currentThread().getContextClassLoader(); }
    @Override public void declareRoles(String... roleNames) {}
    @Override public String getVirtualServerName() { return "vidocq"; }

    @Override public String getRequestCharacterEncoding() { return requestCharacterEncoding; }
    @Override public void setRequestCharacterEncoding(String encoding) { this.requestCharacterEncoding = encoding; }
    @Override public void setRequestCharacterEncoding(java.nio.charset.Charset encoding) {
        this.requestCharacterEncoding = encoding == null ? null : encoding.name();
    }
    @Override public String getResponseCharacterEncoding() { return responseCharacterEncoding; }
    @Override public void setResponseCharacterEncoding(String encoding) { this.responseCharacterEncoding = encoding; }
    @Override public void setResponseCharacterEncoding(java.nio.charset.Charset encoding) {
        this.responseCharacterEncoding = encoding == null ? null : encoding.name();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("dynamic registration not implemented in M2a");
    }
}
