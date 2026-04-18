package fr.vidocq.vidocq.ext.servlet.chappe.container;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registration dynamique d'un servlet créée via {@link jakarta.servlet.ServletContext#addServlet}.
 *
 * <p>Stocke le name, la classe/instance/className, les url-pattern mappings,
 * les init-params et options (load-on-startup, async-supported, run-as, etc.).
 * Sera matérialisée dans le dispatcher par le harness à la fin de la phase d'initialisation.</p>
 */
public final class DynamicServletRegistration implements ServletRegistration.Dynamic {

    private final String name;
    private Servlet instance;
    private Class<? extends Servlet> klass;
    private String className;
    private final Map<String, String> initParams = new LinkedHashMap<>();
    private final Set<String> mappings = new LinkedHashSet<>();
    private int loadOnStartup = -1;
    private boolean asyncSupported;
    private MultipartConfigElement multipart;
    private String runAsRole;
    private ServletSecurityElement security;

    public DynamicServletRegistration(String name, Servlet instance) {
        this.name = name; this.instance = instance;
    }
    public DynamicServletRegistration(String name, Class<? extends Servlet> klass) {
        this.name = name; this.klass = klass;
    }
    public DynamicServletRegistration(String name, String className) {
        this.name = name; this.className = className;
    }

    @Override public String getName() { return name; }
    @Override public String getClassName() {
        if (instance != null) return instance.getClass().getName();
        if (klass != null) return klass.getName();
        return className;
    }
    public Servlet instance() { return instance; }
    public Class<? extends Servlet> klass() { return klass; }

    @Override public boolean setInitParameter(String name, String value) {
        if (initParams.containsKey(name)) return false;
        initParams.put(name, value); return true;
    }
    @Override public String getInitParameter(String name) { return initParams.get(name); }
    @Override public Set<String> setInitParameters(Map<String, String> params) {
        Set<String> conflicts = new LinkedHashSet<>();
        for (var e : params.entrySet()) {
            if (initParams.containsKey(e.getKey())) conflicts.add(e.getKey());
        }
        if (conflicts.isEmpty()) initParams.putAll(params);
        return conflicts;
    }
    @Override public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(initParams);
    }

    @Override public Set<String> addMapping(String... urlPatterns) {
        Set<String> conflicts = new LinkedHashSet<>();
        for (String p : urlPatterns) {
            if (!mappings.add(p)) conflicts.add(p);
        }
        return conflicts;
    }
    @Override public Collection<String> getMappings() { return Collections.unmodifiableSet(mappings); }
    @Override public String getRunAsRole() { return runAsRole; }
    @Override public void setRunAsRole(String roleName) { this.runAsRole = roleName; }

    @Override public void setLoadOnStartup(int loadOnStartup) { this.loadOnStartup = loadOnStartup; }
    public int getLoadOnStartup() { return loadOnStartup; }
    @Override public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        this.security = constraint;
        return Collections.emptySet();
    }
    public ServletSecurityElement getServletSecurity() { return security; }
    @Override public void setMultipartConfig(MultipartConfigElement multipartConfig) {
        this.multipart = multipartConfig;
    }
    public MultipartConfigElement getMultipartConfig() { return multipart; }
    @Override public void setAsyncSupported(boolean isAsyncSupported) {
        this.asyncSupported = isAsyncSupported;
    }
    public boolean isAsyncSupported() { return asyncSupported; }
}
