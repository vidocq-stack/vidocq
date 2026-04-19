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
    private boolean initialized;
    private boolean programmaticListenerActive;
    private Map<String, String> localeEncodingMappings = Map.of();
    private final java.util.LinkedHashMap<String, DynamicServletRegistration> dynamicServlets = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, DynamicFilterRegistration> dynamicFilters = new java.util.LinkedHashMap<>();

    public Map<String, DynamicServletRegistration> dynamicServletRegistrations() {
        return java.util.Collections.unmodifiableMap(dynamicServlets);
    }
    public Map<String, DynamicFilterRegistration> dynamicFilterRegistrations() {
        return java.util.Collections.unmodifiableMap(dynamicFilters);
    }

    /** Active/désactive la phase "programmatic listener init" — pendant celle-ci,
     *  les méthodes de configuration dynamique doivent throw UOE (§4.4.3). */
    public void setProgrammaticListenerActive(boolean active) { this.programmaticListenerActive = active; }

    /** Mapping &lt;locale&gt; → &lt;encoding&gt; issu du {@code web.xml} (Servlet 6.1 §14.4). */
    public void setLocaleEncodingMappings(Map<String, String> mappings) {
        this.localeEncodingMappings = mappings == null ? Map.of() : Map.copyOf(mappings);
    }
    public String encodingForLocale(java.util.Locale locale) {
        if (locale == null) return null;
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        // 1) Mappings explicites du web.xml (§14.4) — prioritaires.
        if (!localeEncodingMappings.isEmpty()) {
            if (country != null && !country.isEmpty()) {
                String full = lang + "-" + country.toLowerCase(java.util.Locale.ROOT);
                String v = localeEncodingMappings.get(full);
                if (v != null) return v;
            }
            String v = localeEncodingMappings.get(lang);
            if (v != null) return v;
        }
        // 2) Défauts du conteneur (table alignée avec Tomcat / Servlet 6.1).
        return DEFAULT_LOCALE_ENCODINGS.get(lang);
    }

    private static final Map<String, String> DEFAULT_LOCALE_ENCODINGS = Map.ofEntries(
            Map.entry("ar", "ISO-8859-6"),
            Map.entry("be", "ISO-8859-5"),
            Map.entry("bg", "ISO-8859-5"),
            Map.entry("ca", "ISO-8859-1"),
            Map.entry("cs", "ISO-8859-2"),
            Map.entry("da", "ISO-8859-1"),
            Map.entry("de", "ISO-8859-1"),
            Map.entry("el", "ISO-8859-7"),
            Map.entry("en", "ISO-8859-1"),
            Map.entry("es", "ISO-8859-1"),
            Map.entry("et", "ISO-8859-1"),
            Map.entry("fi", "ISO-8859-1"),
            Map.entry("fr", "ISO-8859-1"),
            Map.entry("hr", "ISO-8859-2"),
            Map.entry("hu", "ISO-8859-2"),
            Map.entry("is", "ISO-8859-1"),
            Map.entry("it", "ISO-8859-1"),
            Map.entry("iw", "ISO-8859-8"),
            Map.entry("ja", "Shift_JIS"),
            Map.entry("ko", "EUC-KR"),
            Map.entry("lt", "ISO-8859-2"),
            Map.entry("lv", "ISO-8859-2"),
            Map.entry("mk", "ISO-8859-5"),
            Map.entry("nl", "ISO-8859-1"),
            Map.entry("no", "ISO-8859-1"),
            Map.entry("pl", "ISO-8859-2"),
            Map.entry("pt", "ISO-8859-1"),
            Map.entry("ro", "ISO-8859-2"),
            Map.entry("ru", "ISO-8859-5"),
            Map.entry("sh", "ISO-8859-5"),
            Map.entry("sk", "ISO-8859-2"),
            Map.entry("sl", "ISO-8859-2"),
            Map.entry("sq", "ISO-8859-2"),
            Map.entry("sr", "ISO-8859-5"),
            Map.entry("sv", "ISO-8859-1"),
            Map.entry("tr", "ISO-8859-9"),
            Map.entry("uk", "ISO-8859-5"),
            Map.entry("zh", "GB2312"),
            Map.entry("zh_TW", "Big5"));

    public VidocqServletContext(String contextPath) {
        this.contextPath = contextPath;
        this.serverInfo = "Vidocq Servlet/Chappe";
    }

    /** Marqueur de fin d'initialisation (Servlet 6.1 §4.4) — après cet appel,
     *  les méthodes de configuration dynamique doivent throw {@link IllegalStateException}. */
    public void markInitialized() { this.initialized = true; }

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

    private int effectiveMajor = 6;
    private int effectiveMinor = 1;
    public void setEffectiveVersion(int major, int minor) {
        this.effectiveMajor = major; this.effectiveMinor = minor;
    }
    @Override public int getEffectiveMajorVersion() { return effectiveMajor; }
    @Override public int getEffectiveMinorVersion() { return effectiveMinor; }
    @Override public String getMimeType(String file) {
        if (file == null) return null;
        int dot = file.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = file.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "class" -> "application/x-java-class";
            case "html", "htm" -> "text/html";
            case "txt" -> "text/plain";
            case "xml" -> "text/xml";
            case "json" -> "application/json";
            case "css" -> "text/css";
            case "js"  -> "application/javascript";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            default -> java.net.URLConnection.guessContentTypeFromName(file);
        };
    }
    @Override public Set<String> getResourcePaths(String path) { return Set.of(); }
    @Override public java.net.URL getResource(String path) { return null; }
    @Override public java.io.InputStream getResourceAsStream(String path) { return null; }
    @Override public RequestDispatcher getRequestDispatcher(String path) {
        // Servlet 6.1 §9.1 : retourne un dispatcher non-null pour tout chemin relatif au
        // contexte, même si aucun servlet n'est mappé (un forward/include sur ce path
        // renverra 404 si aucune ressource correspond).
        if (path == null) return null;
        if (!path.startsWith("/")) return null; // doit être absolu dans le contexte
        if (dispatchResolver == null || dispatchInvoker == null) return null;
        String tmp = path;
        if (!contextPath.equals("/") && path.startsWith(contextPath)) {
            tmp = path.substring(contextPath.length());
            if (tmp.isEmpty()) tmp = "/";
        }
        final String resolvePath = tmp;
        return dispatchResolver.resolve(resolvePath)
                .<RequestDispatcher>map(t -> new RequestDispatcherImpl(t, dispatchInvoker))
                .orElseGet(() -> RequestDispatcherImpl.notFound(resolvePath));
    }
    @Override public RequestDispatcher getNamedDispatcher(String name) { return null; }
    @Override public void log(String msg) { System.getLogger("servlet.log").log(System.Logger.Level.INFO, msg); }
    @Override public void log(String message, Throwable throwable) {
        System.getLogger("servlet.log").log(System.Logger.Level.ERROR, message, throwable);
    }
    @Override public String getRealPath(String path) { return null; }
    @Override public String getServerInfo() { return serverInfo; }
    @Override public String getInitParameter(String name) {
        if (name == null) throw new NullPointerException("name is null");
        return initParameters.get(name);
    }
    @Override public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
    @Override public boolean setInitParameter(String name, String value) {
        if (name == null) throw new NullPointerException("name is null");
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        return initParameters.putIfAbsent(name, value) == null;
    }

    @Override public Object getAttribute(String name) {
        // Servlet 6.1 §4.0 : NullPointerException sur name null.
        if (name == null) throw new NullPointerException("name is null");
        return attributes.get(name);
    }
    @Override public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
    @Override public void setAttribute(String name, Object object) {
        if (name == null) throw new NullPointerException("name is null");
        if (object == null) { removeAttribute(name); return; }
        Object previous = attributes.put(name, object);
        if (previous == null) listenerRegistry.fireContextAttributeAdded(this, name, object);
        else listenerRegistry.fireContextAttributeReplaced(this, name, previous);
    }
    @Override public void removeAttribute(String name) {
        if (name == null) throw new NullPointerException("name is null");
        Object previous = attributes.remove(name);
        if (previous != null) listenerRegistry.fireContextAttributeRemoved(this, name, previous);
    }

    @Override public String getServletContextName() { return "vidocq"; }

    // ---- Dynamic registration — not supported in M2a ----
    // Servlet 6.1 §4.4 : après initialisation du contexte, ces méthodes doivent throw
    // IllegalStateException. Avant initialisation, elles throw UnsupportedOperationException
    // tant que la feature n'est pas implémentée.

    @Override public ServletRegistration.Dynamic addServlet(String name, String className) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        if (dynamicServlets.containsKey(name)) return null;
        var r = new DynamicServletRegistration(name, className);
        dynamicServlets.put(name, r);
        return r;
    }
    @Override public ServletRegistration.Dynamic addServlet(String name, Servlet servlet) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        if (dynamicServlets.containsKey(name)) return null;
        var r = new DynamicServletRegistration(name, servlet);
        dynamicServlets.put(name, r);
        return r;
    }
    @Override public ServletRegistration.Dynamic addServlet(String name, Class<? extends Servlet> c) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        if (dynamicServlets.containsKey(name)) return null;
        var r = new DynamicServletRegistration(name, c);
        dynamicServlets.put(name, r);
        return r;
    }
    @Override public ServletRegistration.Dynamic addJspFile(String name, String jspFile) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        // JSP non supporté — on enregistre quand même la registration pour les tests qui
        // vérifient le flux de configuration (la request vers cette URL renverra 404).
        if (dynamicServlets.containsKey(name)) return null;
        var r = new DynamicServletRegistration(name, (String) null);
        dynamicServlets.put(name, r);
        return r;
    }
    @Override public <T extends Servlet> T createServlet(Class<T> c) throws jakarta.servlet.ServletException {
        if (programmaticListenerActive) throw programmaticForbidden();
        try { return c.getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException e) {
            throw new jakarta.servlet.ServletException("cannot instantiate servlet " + c.getName(), e);
        }
    }
    @Override public ServletRegistration getServletRegistration(String name) {
        if (programmaticListenerActive) throw programmaticForbidden();
        return dynamicServlets.get(name);
    }
    @Override public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        if (programmaticListenerActive) throw programmaticForbidden();
        return java.util.Collections.unmodifiableMap(dynamicServlets);
    }
    @Override public FilterRegistration.Dynamic addFilter(String name, String className) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        if (dynamicFilters.containsKey(name)) return null;
        var r = new DynamicFilterRegistration(name, className);
        dynamicFilters.put(name, r);
        return r;
    }
    @Override public FilterRegistration.Dynamic addFilter(String name, Filter f) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        if (dynamicFilters.containsKey(name)) return null;
        var r = new DynamicFilterRegistration(name, f);
        dynamicFilters.put(name, r);
        return r;
    }
    @Override public FilterRegistration.Dynamic addFilter(String name, Class<? extends Filter> c) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        if (dynamicFilters.containsKey(name)) return null;
        var r = new DynamicFilterRegistration(name, c);
        dynamicFilters.put(name, r);
        return r;
    }
    @Override public <T extends Filter> T createFilter(Class<T> c) throws jakarta.servlet.ServletException {
        if (programmaticListenerActive) throw programmaticForbidden();
        try { return c.getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException e) {
            throw new jakarta.servlet.ServletException("cannot instantiate filter " + c.getName(), e);
        }
    }
    @Override public FilterRegistration getFilterRegistration(String name) {
        if (programmaticListenerActive) throw programmaticForbidden();
        return dynamicFilters.get(name);
    }
    @Override public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        if (programmaticListenerActive) throw programmaticForbidden();
        return java.util.Collections.unmodifiableMap(dynamicFilters);
    }

    // ---- Listeners ----
    // Servlet 6.1 §4.4 : addListener n'est autorisé que pendant l'initialisation
    // (SCI.onStartup ou contextInitialized d'un listener non-programmatique).

    @Override public void addListener(String className) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        try {
            Class<?> c = Class.forName(className, true, getClassLoader());
            addProgrammaticListener((java.util.EventListener) c.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot load listener " + className, e);
        }
    }
    @Override public <T extends java.util.EventListener> void addListener(T t) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        addProgrammaticListener(t);
    }
    @Override public void addListener(Class<? extends java.util.EventListener> listenerClass) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        try {
            addProgrammaticListener(listenerClass.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot instantiate " + listenerClass, e);
        }
    }
    @Override public <T extends java.util.EventListener> T createListener(Class<T> c) throws jakarta.servlet.ServletException {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        try { return c.getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException e) {
            throw new jakarta.servlet.ServletException("cannot instantiate listener " + c.getName(), e);
        }
    }

    private void addProgrammaticListener(java.util.EventListener l) {
        if (listenerRegistry == null) listenerRegistry = new ListenerRegistry();
        listenerRegistry.register(l, true);
    }

    private static UnsupportedOperationException programmaticForbidden() {
        return new UnsupportedOperationException(
                "dynamic configuration not allowed from a programmatic listener");
    }

    // ---- Sessions — stubs ----

    @Override public SessionCookieConfig getSessionCookieConfig() { throw dynamicUnavailable(); }
    @Override public void setSessionTrackingModes(Set<SessionTrackingMode> modes) {
        if (initialized) throw alreadyInitialized();
        throw dynamicUnavailable();
    }
    @Override public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return EnumSet.of(SessionTrackingMode.COOKIE);
    }
    @Override public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return EnumSet.of(SessionTrackingMode.COOKIE);
    }
    @Override public int getSessionTimeout() { return sessionTimeout; }
    @Override public void setSessionTimeout(int sessionTimeout) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        this.sessionTimeout = sessionTimeout;
    }
    /** Setter interne (contourne les checks) — utilisé par le harness pour
     *  propager la valeur de {@code <session-timeout>} du web.xml. */
    public void setSessionTimeoutInternal(int minutes) { this.sessionTimeout = minutes; }

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

    private RuntimeException dynamicUnavailable() {
        // Servlet 6.1 §4.4 : après initialisation, IllegalStateException est requis.
        if (initialized) return alreadyInitialized();
        return new UnsupportedOperationException("dynamic registration not implemented in M2a");
    }

    private static IllegalStateException alreadyInitialized() {
        return new IllegalStateException("ServletContext already initialized");
    }
}
