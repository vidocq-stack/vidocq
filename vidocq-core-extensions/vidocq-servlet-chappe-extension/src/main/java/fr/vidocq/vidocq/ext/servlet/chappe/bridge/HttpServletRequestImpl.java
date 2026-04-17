package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import fr.vidocq.chappe.api.Request;
import fr.vidocq.vidocq.ext.servlet.chappe.http.CookieCodec;
import fr.vidocq.vidocq.ext.servlet.chappe.session.HttpSessionImpl;
import fr.vidocq.vidocq.ext.servlet.chappe.session.SessionManager;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapte un {@link Request} Chappe en {@link HttpServletRequest} Servlet 6.1.
 *
 * <p>Implémentation minimale pour le jalon M2a — beaucoup de méthodes renvoient
 * {@link UnsupportedOperationException}. Elles seront étoffées au fil des jalons
 * suivants (session, async, multipart, security, upgrade, etc.).</p>
 */
public final class HttpServletRequestImpl implements HttpServletRequest {

    private final Request chappe;
    private final String contextPath;
    private final String servletPath;
    private final String pathInfo;
    private final ServletContext servletContext;
    private final SessionManager sessionManager;
    private final Map<String, Object> attributes = new HashMap<>();
    private String characterEncoding;
    private ServletInputStream inputStream;
    private BufferedReader reader;
    private Cookie[] parsedCookies;
    private String requestedSessionId;
    private HttpSessionImpl currentSession;

    public HttpServletRequestImpl(Request chappe, ServletContext ctx,
                                  String contextPath, String servletPath, String pathInfo) {
        this(chappe, ctx, contextPath, servletPath, pathInfo, null);
    }

    public HttpServletRequestImpl(Request chappe, ServletContext ctx,
                                  String contextPath, String servletPath, String pathInfo,
                                  SessionManager sessionManager) {
        this.chappe = chappe;
        this.servletContext = ctx;
        this.contextPath = contextPath;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        this.sessionManager = sessionManager;
    }

    // ---- Request line & URI ----

    @Override public String getAuthType() { return null; }
    @Override public String getMethod() { return chappe.method().name(); }
    @Override public String getProtocol() { return chappe.version().toString(); }
    @Override public String getScheme() { return chappe.scheme(); }
    @Override public boolean isSecure() { return chappe.isSecure(); }
    @Override public String getRequestURI() { return chappe.path(); }
    @Override public StringBuffer getRequestURL() {
        StringBuffer sb = new StringBuffer();
        sb.append(chappe.scheme()).append("://").append(getServerName());
        int port = getServerPort();
        if (("http".equals(chappe.scheme()) && port != 80)
                || ("https".equals(chappe.scheme()) && port != 443)) {
            sb.append(':').append(port);
        }
        sb.append(chappe.path());
        return sb;
    }
    @Override public String getContextPath() { return contextPath; }
    @Override public String getServletPath() { return servletPath; }
    @Override public String getPathInfo() { return pathInfo; }
    @Override public String getPathTranslated() { return null; }
    @Override public String getQueryString() {
        String q = chappe.query();
        return q == null || q.isEmpty() ? null : q;
    }
    @Override public String getRequestId() { return ""; }
    @Override public String getProtocolRequestId() { return ""; }

    // ---- Headers ----

    @Override public String getHeader(String name) {
        return chappe.headers().first(name).orElse(null);
    }
    @Override public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(chappe.headers().all(name));
    }
    @Override public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        for (var e : chappe.headers()) names.add(e.name());
        return Collections.enumeration(names);
    }
    @Override public int getIntHeader(String name) {
        String v = getHeader(name);
        if (v == null) return -1;
        return Integer.parseInt(v);
    }
    @Override public long getDateHeader(String name) {
        if (getHeader(name) == null) return -1;
        throw new UnsupportedOperationException("getDateHeader parsing not implemented");
    }
    @Override public String getContentType() { return getHeader("Content-Type"); }
    @Override public int getContentLength() {
        long l = getContentLengthLong();
        return l > Integer.MAX_VALUE ? -1 : (int) l;
    }
    @Override public long getContentLengthLong() {
        String v = getHeader("Content-Length");
        return v == null ? -1 : Long.parseLong(v);
    }

    // ---- Parameters (query-string only for this milestone) ----

    @Override public String getParameter(String name) {
        return chappe.queryParams().get(name);
    }
    @Override public Enumeration<String> getParameterNames() {
        return Collections.enumeration(chappe.queryParams().keySet());
    }
    @Override public String[] getParameterValues(String name) {
        String v = chappe.queryParams().get(name);
        return v == null ? null : new String[] {v};
    }
    @Override public Map<String, String[]> getParameterMap() {
        Map<String, String[]> map = new HashMap<>();
        for (Map.Entry<String, String> e : chappe.queryParams().entrySet()) {
            map.put(e.getKey(), new String[] {e.getValue()});
        }
        return Collections.unmodifiableMap(map);
    }

    // ---- Body ----

    @Override public ServletInputStream getInputStream() throws IOException {
        if (reader != null) throw new IllegalStateException("getReader() already called");
        if (inputStream == null) {
            inputStream = new ServletInputStreamImpl(chappe.body().asInputStream());
        }
        return inputStream;
    }
    @Override public BufferedReader getReader() throws IOException {
        if (inputStream != null) throw new IllegalStateException("getInputStream() already called");
        if (reader == null) {
            Charset cs = characterEncoding == null
                    ? StandardCharsets.UTF_8 : Charset.forName(characterEncoding);
            reader = new BufferedReader(new InputStreamReader(
                    chappe.body().asInputStream(), cs));
        }
        return reader;
    }
    @Override public String getCharacterEncoding() { return characterEncoding; }
    @Override public void setCharacterEncoding(String env) {
        this.characterEncoding = env;
    }
    @Override public void setCharacterEncoding(Charset encoding) {
        this.characterEncoding = encoding == null ? null : encoding.name();
    }

    // ---- Connection / server ----

    @Override public String getServerName() {
        String host = getHeader("Host");
        if (host != null) {
            int idx = host.indexOf(':');
            return idx < 0 ? host : host.substring(0, idx);
        }
        return chappe.localAddress() != null ? chappe.localAddress().getHostString() : "localhost";
    }
    @Override public int getServerPort() {
        String host = getHeader("Host");
        if (host != null) {
            int idx = host.indexOf(':');
            if (idx >= 0) return Integer.parseInt(host.substring(idx + 1));
        }
        return chappe.localAddress() != null ? chappe.localAddress().getPort() : -1;
    }
    @Override public String getRemoteAddr() {
        var addr = chappe.remoteAddress();
        return addr == null ? "" : addr.getAddress().getHostAddress();
    }
    @Override public String getRemoteHost() {
        var addr = chappe.remoteAddress();
        return addr == null ? "" : addr.getHostString();
    }
    @Override public int getRemotePort() {
        var addr = chappe.remoteAddress();
        return addr == null ? -1 : addr.getPort();
    }
    @Override public String getLocalAddr() {
        var addr = chappe.localAddress();
        return addr == null ? "" : addr.getAddress().getHostAddress();
    }
    @Override public String getLocalName() {
        var addr = chappe.localAddress();
        return addr == null ? "" : addr.getHostString();
    }
    @Override public int getLocalPort() {
        var addr = chappe.localAddress();
        return addr == null ? -1 : addr.getPort();
    }

    // ---- Attributes ----

    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
    @Override public void setAttribute(String name, Object o) {
        if (o == null) { removeAttribute(name); return; }
        Object previous = attributes.put(name, o);
        fr.vidocq.vidocq.ext.servlet.chappe.listener.ListenerRegistry reg = servletContextRegistry();
        if (reg == null) return;
        if (previous == null) reg.fireRequestAttributeAdded(servletContext, this, name, o);
        else reg.fireRequestAttributeReplaced(servletContext, this, name, previous);
    }
    @Override public void removeAttribute(String name) {
        Object previous = attributes.remove(name);
        if (previous == null) return;
        var reg = servletContextRegistry();
        if (reg != null) reg.fireRequestAttributeRemoved(servletContext, this, name, previous);
    }

    private fr.vidocq.vidocq.ext.servlet.chappe.listener.ListenerRegistry servletContextRegistry() {
        if (servletContext instanceof fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext v) {
            return v.listenerRegistry();
        }
        return null;
    }

    // ---- Locale ----

    @Override public Locale getLocale() { return Locale.getDefault(); }
    @Override public Enumeration<Locale> getLocales() {
        return Collections.enumeration(List.of(Locale.getDefault()));
    }

    // ---- Dispatcher / context ----

    @Override public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }
    @Override public ServletContext getServletContext() { return servletContext; }
    @Override public RequestDispatcher getRequestDispatcher(String path) {
        throw new UnsupportedOperationException("request dispatcher not implemented");
    }
    @Override public HttpServletMapping getHttpServletMapping() {
        throw new UnsupportedOperationException();
    }

    // ---- Unimplemented Servlet 6.1 features (future milestones) ----

    @Override public Cookie[] getCookies() {
        if (parsedCookies == null) {
            parsedCookies = CookieCodec.parseCookieHeader(getHeader("Cookie"))
                    .toArray(new Cookie[0]);
        }
        return parsedCookies.length == 0 ? null : parsedCookies;
    }
    @Override public String getRemoteUser() { return null; }
    @Override public boolean isUserInRole(String role) { return false; }
    @Override public Principal getUserPrincipal() { return null; }
    @Override public String getRequestedSessionId() {
        if (requestedSessionId == null) {
            requestedSessionId = extractSessionIdFromCookies();
        }
        return requestedSessionId;
    }
    @Override public HttpSession getSession(boolean create) {
        if (currentSession != null && !currentSession.isInvalidated()) return currentSession;
        if (sessionManager == null) {
            if (create) throw new IllegalStateException("no SessionManager bound");
            return null;
        }
        String id = getRequestedSessionId();
        HttpSessionImpl existing = id == null ? null : sessionManager.find(id);
        if (existing != null) {
            currentSession = existing;
            return existing;
        }
        if (!create) return null;
        currentSession = sessionManager.createNew();
        return currentSession;
    }
    @Override public HttpSession getSession() { return getSession(true); }
    @Override public String changeSessionId() {
        HttpSession s = getSession(false);
        if (s == null) throw new IllegalStateException("no session");
        throw new UnsupportedOperationException("changeSessionId not implemented");
    }
    @Override public boolean isRequestedSessionIdValid() {
        String id = getRequestedSessionId();
        return id != null && sessionManager != null && sessionManager.find(id) != null;
    }
    @Override public boolean isRequestedSessionIdFromCookie() { return getRequestedSessionId() != null; }
    @Override public boolean isRequestedSessionIdFromURL() { return false; }

    private String extractSessionIdFromCookies() {
        Cookie[] cookies = getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (SessionManager.COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    public HttpSessionImpl boundSession() { return currentSession; }
    @Override public boolean authenticate(jakarta.servlet.http.HttpServletResponse response) { return false; }
    @Override public void login(String username, String password) throws ServletException {
        throw new ServletException("login not implemented");
    }
    @Override public void logout() {}
    @Override public Collection<Part> getParts() {
        throw new UnsupportedOperationException("multipart not implemented");
    }
    @Override public Part getPart(String name) { throw new UnsupportedOperationException(); }
    @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw new UnsupportedOperationException("upgrade not implemented");
    }
    @Override public AsyncContext startAsync() {
        throw new UnsupportedOperationException("async not implemented");
    }
    @Override public AsyncContext startAsync(jakarta.servlet.ServletRequest req, ServletResponse res) {
        throw new UnsupportedOperationException();
    }
    @Override public boolean isAsyncStarted() { return false; }
    @Override public boolean isAsyncSupported() { return false; }
    @Override public AsyncContext getAsyncContext() { throw new IllegalStateException("no async context"); }
    @Override public ServletConnection getServletConnection() {
        throw new UnsupportedOperationException("ServletConnection not implemented");
    }
}
