package io.vidocq.cervantes.tck.arquillian;

import io.vidocq.cassini.cdi.vauban.CassiniScopeExtension;
import io.vidocq.cassini.cdi.vauban.VaubanBeanProvider;
import io.vidocq.cassini.cdi.vauban.VaubanRequestScopeFilter;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.cervantes.cdi.CervantesClaimExtension;
import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer;
import io.vidocq.cervantes.cdi.internal.JsonWebTokenProducer;
import io.vidocq.cervantes.cdi.internal.ClaimResolver;
import io.vidocq.cervantes.cdi.ClaimSyntheticCreator;
import io.vidocq.cervantes.cassini.JwtAuthenticationFilter;
import io.vidocq.cervantes.cassini.RolesAllowedDynamicFeature;
import io.vidocq.ravel.cdi.ConfigCdiExtension;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.Provider;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Arquillian adapter that deploys a {@link WebArchive} of TCK MP JWT 2.1
 * on a complete Cervantes stack: Vauban CDI + Cassini JAX-RS + HTTP Chappe.
 *
 * <p>Deployed strategy:</p>
 * <ol>
 *   <li>Extraire {@code META-INF/microprofile-config.properties} de l'archive et les
 *       export them as system properties (Ravel reads system properties).</li>
 * <li>Stop all VaubanPrevious container.</li>
 *   <li>Collect the archive classes (TCK application beans).</li>
 * <li>Starting a VaubanContainer with Cervante Beans (BCE extension, CDI producers)
 *       and the archive classes.</li>
 *   <li>Build the Cassini runtime via {@link CassiniStack.Builder#beanProvider} with
 * {@link VaubanBeanProvider} — classes {@code @Path} have been discovered since
 * BeanManager (real class, not the CDI proxy) and substantiated via CDI.ZZPH0ZZ
 * <li>Go to Chappe + ephemeral port + ContextStrippingHandler.ZZPH1ZZ
 *   <li>Return Servlet 3.0 ProtocolMetaData.</li>
 * </ol>
 *
 * <p>Protocol {@code Servlet 3.0}: Arquillian TCK tests use HTTP client
 * Java standard vers l'URL de base du container (baseUrl = http://127.0.0.1:port/ctxPath).</p>
 */
public class CervantesJwtDeployableContainer implements DeployableContainer<CervantesContainerConfiguration> {

    private CervantesContainerConfiguration config;

    /** archive name → chappe Server instance (pour undeploy). */
    private final Map<String, Server> serverMap = new LinkedHashMap<>();

    /** system property keys exported during last deploy (cleared on undeploy). */
    private final List<String> exportedKeys = new ArrayList<>();

    @Override
    public Class<CervantesContainerConfiguration> getConfigurationClass() {
        return CervantesContainerConfiguration.class;
    }

    @Override
    public void setup(CervantesContainerConfiguration configuration) {
        this.config = configuration;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override
    public void start() throws LifecycleException {
        // no-op: Vauban starts per deployment
    }

    @Override
    public void stop() throws LifecycleException {
        stopAll();
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("Only WebArchive supported, got " + archive.getClass());
        }

        // 1. Extract MP Config properties from archive and set as system properties
        Properties configProps = extractConfig(war);
        exportToSystemProperties(configProps);

        // 2. Stop any existing Vauban container
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }

        // 3. Collect bean classes from the archive
        List<Class<?>> archiveBeanClasses = extractBeanClasses(war);

        // 4. Boot Vauban with Cervantes CDI beans + archive classes
        var vaubanBuilder = VaubanContainer.builder()
                // CassiniScopeExtension: makes @Path classes @RequestScoped and @Provider @Dependent
                // so CDI injection (@Inject JsonWebToken etc.) works in resource classes
                .addBeanClass(CassiniScopeExtension.class)
                // Ravel Build Compatible Extension for @ConfigProperty injection (mp.jwt.verify.audiences etc.)
                .addBeanClass(ConfigCdiExtension.class)
                // Cervantes Build Compatible Extension for @Claim injection
                .addBeanClass(CervantesClaimExtension.class)
                // CDI beans: JwtValidator producer, JsonWebToken producer, JsonWebTokenContext
                .addBeanClass(JwtAuthConfigProducer.class)
                .addBeanClass(JsonWebTokenProducer.class)
                .addBeanClass(JsonWebTokenContext.class)
                // Claim resolution support
                .addBeanClass(ClaimResolver.class)
                .addBeanClass(ClaimSyntheticCreator.class)
                // JAX-RS security filter (ApplicationScoped CDI bean)
                .addBeanClass(JwtAuthenticationFilter.class);

        for (Class<?> c : archiveBeanClasses) {
            vaubanBuilder.addBeanClass(c);
        }
        vaubanBuilder.build();

        VaubanContainer container = VaubanContainer.current();

        // 5. Determine context path from archive name
        String archiveName = war.getName();
        String ctxName = archiveName == null ? "" : archiveName;
        if (ctxName.endsWith(".war")) ctxName = ctxName.substring(0, ctxName.length() - 4);
        String contextPath = ctxName.isEmpty() ? "/" : "/" + ctxName;

        // 6. Find Application class in archive (if any)
        Application application = null;
        Class<?> applicationClass = null;
        for (Class<?> cls : archiveBeanClasses) {
            if (Application.class.isAssignableFrom(cls) && !cls.equals(Application.class)) {
                applicationClass = cls;
                try {
                    application = (Application) cls.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    System.err.println("[CervantesJwtTCK] Could not instantiate Application " + cls + ": " + e.getMessage());
                }
                break;
            }
        }

        //Determines @ApplicationPath prefix (e.g. "/pem", "/jwks") — strip it before routing.
        //JAX-RS spec §2.1: @ApplicationPath definitions the base URI relative to the deployment root.
        String applicationPath = "";
        if (applicationClass != null) {
            jakarta.ws.rs.ApplicationPath ap = applicationClass.getAnnotation(jakarta.ws.rs.ApplicationPath.class);
            if (ap != null) {
                String apv = ap.value();
                if (!apv.startsWith("/")) apv = "/" + apv;
                if (!apv.equals("/")) applicationPath = apv;
            }
        }

        // 7. Build CDI-aware Cassini stack via VaubanBeanProvider.
        // VaubanBeanProvider.getResourceClasses() scans BeanManager for real bean classes
        // (not CDI proxies) annotated with @Path or @Provider, then returns them so
        // CassiniStackBuilderImpl can register routes against the real class (with @Path).
        // VaubanBeanProvider.getBean(cls) returns the CDI proxy for actual dispatch.
        //
        // IMPORTANT: JwtAuthenticationFilter has @Provider @PreMatching on the *real* class.
        // CassiniStackBuilderImpl.build() creates a CDI proxy for it and registers it
        //via FilterEntry.of(proxy) — which checks proxy.getClass() for @PreMatching and
        // finds nothing (CDI proxies don't carry annotations). Result: filter registers as
        //post-matching, and setSecurityContext() throws IllegalStateException (§6.6).
        //
        // Fix: use a wrapping BeanProvider that excludes JwtAuthenticationFilter from
        // getResourceClasses(), then pass PreMatchingJwtAuthDelegate explicitly.
        // JwtAuthenticationFilter remains in Vauban CDI for injection purposes.
        VaubanBeanProvider innerBp = new VaubanBeanProvider(container);
        io.vidocq.cervantes.cdi.JsonWebTokenContext tokenContext =
            container.select(io.vidocq.cervantes.cdi.JsonWebTokenContext.class);
        io.vidocq.cassini.spi.bean.BeanProvider beanProvider =
            new CervantesHarnessBeanProvider(innerBp, tokenContext);

        // JWT auth filter wrapper: JwtAuthenticationFilter CDI proxy has no @PreMatching
        // on its generated proxy class. PreMatchingJwtAuthDelegate carries the annotation
        // directly and delegates to the CDI-managed proxy.
        JwtAuthenticationFilter authFilterProxy = container.select(JwtAuthenticationFilter.class);
        PreMatchingJwtAuthDelegate jwtDelegate = new PreMatchingJwtAuthDelegate(authFilterProxy);

        CassiniStack.Builder stackBuilder = CassiniStack.builder()
                .beanProvider(beanProvider)
                // VaubanRequestScopeFilter activates/deactivates CDI @RequestScope per request.
                // Not @Provider-annotated so must be passed explicitly.
                .provider(new VaubanRequestScopeFilter(container))
                // JWT authentication (pre-matching, sets SecurityContext)
                .provider(jwtDelegate)
                // @RolesAllowed / @PermitAll / @DenyAll enforcement
                .provider(new RolesAllowedDynamicFeature());

        if (application != null) {
            stackBuilder.application(application);
        }

        CassiniStack stack = stackBuilder.build();
        ChappeHttpAdapter chappeAdapter = new ChappeHttpAdapter(stack.adapter());

        // 8. Wrap with ContextStrippingHandler.
        // The full stripping prefix = contextPath + applicationPath.
        // e.g. for archive "PublicKeyAsPEMLocationTest.war" with PEMApplication(@ApplicationPath("/pem")):
        //   request path = /PublicKeyAsPEMLocationTest/pem/endp/publicKey4k
        //   stripping prefix = /PublicKeyAsPEMLocationTest/pem
        //→ Cassini sees /endp/publicKey4k
        String stripPrefix = contextPath;
        if (!applicationPath.isEmpty()) {
            stripPrefix = "/".equals(contextPath) ? applicationPath : contextPath + applicationPath;
        }
        Handler rootHandler;
        if ("/".equals(stripPrefix)) {
            rootHandler = chappeAdapter;
        } else {
            final String prefix = stripPrefix;
            final Handler delegate = chappeAdapter;
            rootHandler = new ContextStrippingHandler(prefix, delegate);
        }

        // 9. Start Chappe server on ephemeral port
        int port = findFreePort();
        Server server = Server.builder()
                .host("127.0.0.1")
                .port(port)
                .handler(rootHandler)
                .build();
        server.start();
        serverMap.put(war.getName(), server);

        System.err.println("[CervantesJwtTCK] deployed archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + port
                + " contextPath=" + contextPath
                + " applicationPath=" + applicationPath
                + " stripPrefix=" + stripPrefix);

        // Rewrite mp.jwt.tck.jwks.baseURL and any mp.jwt.verify.publickey.location / mp.jwt.decrypt.key.location
        // that contain "http://localhost:8080/" to point at our actual ephemeral port + context path.
        // The TCK creates deployment WARs with a hardcoded baseURL (default: http://localhost:8080/) before
        // the container is started; we fix the system properties after starting so the lazy resolver sees
        // the correct URL at request time.
        // Base URL for self-referential key endpoints: http://127.0.0.1:<port>/<ctxName>/
        String selfBaseUrl = "http://127.0.0.1:" + port + contextPath + "/";
        System.setProperty("mp.jwt.tck.jwks.baseURL", selfBaseUrl);
        exportedKeys.add("mp.jwt.tck.jwks.baseURL");
        rewriteUrlSystemProperty("mp.jwt.verify.publickey.location", selfBaseUrl);
        rewriteUrlSystemProperty("mp.jwt.decrypt.key.location", selfBaseUrl);

        // 10. Return ProtocolMetaData Servlet 3.0
        // contextRoot = WAR context path only (without applicationPath).
        // The TCK tests build their endpoint URL as: baseURL + applicationPath + resourcePath.
        // e.g. baseURL = http://host:port/PublicKeyAsPEMLocationTest/
        //      test appends "pem/endp/verifyKeyLocationAsPEMResource"
        //      full URL = http://host:port/PublicKeyAsPEMLocationTest/pem/endp/...
        // Our ContextStrippingHandler strips /PublicKeyAsPEMLocationTest/pem leaving /endp/...
        ProtocolMetaData pmd = new ProtocolMetaData();
        HTTPContext ctx = new HTTPContext(config.getHost(), port);
        String contextRoot = "/".equals(contextPath) ? "/" : contextPath;
        ctx.add(new Servlet("_cervantes_jwt", contextRoot));
        pmd.addContext(ctx);
        return pmd;
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        clearSystemProperties();
        Server server = serverMap.remove(archive.getName());
        if (server != null) {
            try { server.stop(); } catch (Exception ignored) {}
        }
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }
        System.err.println("[CervantesJwtTCK] undeployed archive=" + archive.getName());
    }

    @Override
    public void deploy(Descriptor descriptor) {}

    @Override
    public void undeploy(Descriptor descriptor) {}

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void stopAll() {
        clearSystemProperties();
        for (Server s : serverMap.values()) {
            try { s.stop(); } catch (Exception ignored) {}
        }
        serverMap.clear();
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("Could not find a free port", e);
        }
    }

    /** Extracts {@code META-INF/microprofile-config.properties} from the archive root. */
    private static Properties extractConfig(Archive<?> archive) {
        Properties props = new Properties();
        // Try common locations in priority order
        String[] candidates = {
            "/META-INF/microprofile-config.properties",
            "/WEB-INF/classes/META-INF/microprofile-config.properties"
        };
        for (String path : candidates) {
            Node node = archive.get(path);
            if (node != null && node.getAsset() != null) {
                try (InputStream is = node.getAsset().openStream()) {
                    props.load(is);
                    System.err.println("[CervantesJwtTCK] loaded config from " + path + " — " + props);
                    return props;
                } catch (Exception e) {
                    System.err.println("[CervantesJwtTCK] failed to load " + path + ": " + e.getMessage());
                }
            }
        }
        return props;
    }

    private void exportToSystemProperties(Properties props) {
        for (String key : props.stringPropertyNames()) {
            System.setProperty(key, props.getProperty(key));
            exportedKeys.add(key);
        }
    }

    /**
     * Rewrites the URL value of a system property if it starts with the TCK default base URL
     * ({@code http://localhost:8080/} or {@code http://localhost:8080}).
     * Replaces the base part with {@code selfBaseUrl} (our actual server URL).
     * No-op if the property is not set or does not start with a localhost:8080 URL.
     */
    private void rewriteUrlSystemProperty(String key, String selfBaseUrl) {
        String value = System.getProperty(key);
        if (value == null) return;
        // The TCK may use http://localhost:8080/ or just http://localhost:8080 as base
        String[] tcKBases = {"http://localhost:8080/", "http://localhost:8080"};
        for (String base : tcKBases) {
            if (value.startsWith(base)) {
                String suffix = value.substring(base.length());
                String rewritten = selfBaseUrl + suffix;
                System.setProperty(key, rewritten);
                System.err.println("[CervantesJwtTCK] rewrote " + key + " from " + value + " → " + rewritten);
                return;
            }
        }
    }

    private void clearSystemProperties() {
        for (String key : exportedKeys) {
            System.clearProperty(key);
        }
        exportedKeys.clear();
    }

    /**
     * Collects bean/resource classes from the archive's content.
     * Uses archive.getContent() to enumerate all entries.
     * Classes from TCK itself are accessible via the test classloader (TCK jar is on classpath).
     */
    private static List<Class<?>> extractBeanClasses(Archive<?> archive) {
        List<Class<?>> classes = new ArrayList<>();
        var cl = Thread.currentThread().getContextClassLoader();

        for (Map.Entry<org.jboss.shrinkwrap.api.ArchivePath, Node> entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get();
            // Only process .class files, skip inner classes
            if (!path.endsWith(".class") || path.contains("$")) continue;
            // Skip META-INF and WEB-INF/lib
            if (path.contains("WEB-INF/lib/")) continue;
            String className = pathToClassName(path);
            if (className == null) continue;
            try {
                Class<?> cls = cl.loadClass(className);
                if (isUsableClass(cls)) classes.add(cls);
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
        }

        return classes;
    }

    private static String pathToClassName(String path) {
        // /WEB-INF/classes/org/example/Foo.class or /org/example/Foo.class
        String p = path;
        if (p.startsWith("/WEB-INF/classes/")) p = p.substring("/WEB-INF/classes/".length());
        else if (p.startsWith("WEB-INF/classes/")) p = p.substring("WEB-INF/classes/".length());
        else if (p.startsWith("/")) p = p.substring(1);
        if (!p.endsWith(".class")) return null;
        return p.substring(0, p.length() - 6).replace('/', '.');
    }

    private static boolean isUsableClass(Class<?> cls) {
        int mod = cls.getModifiers();
        return java.lang.reflect.Modifier.isPublic(mod)
                && !java.lang.reflect.Modifier.isAbstract(mod)
                && !cls.isInterface()
                && !cls.isEnum()
                && !cls.isAnnotation();
    }

    /**
     * Wrapper {@code @PreMatching} autour du CDI proxy {@link JwtAuthenticationFilter}.
     *
     * <p>The CDI proxy generated by Vauban does not have the {@code @PreMatching} annotation on its
     * generated class — the {@code FilterRegistry} Cassini could not detect it
     * as a pre-match. This wrapper carries the annotation directly and delegates to proxy.ZZPH0ZZ
     */
    @Provider
    @PreMatching
    @Priority(Priorities.AUTHENTICATION)
    static final class PreMatchingJwtAuthDelegate implements ContainerRequestFilter {
        private final JwtAuthenticationFilter delegate;

        PreMatchingJwtAuthDelegate(JwtAuthenticationFilter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            delegate.filter(requestContext);
        }
    }

    /**
     * BeanProvider wrapper that:
     * <ol>
     *   <li>Excludes {@link JwtAuthenticationFilter} from {@link #getResourceClasses()} so that
     *       CassiniStackBuilderImpl does not create a CDI proxy for it and register it as a
     *       post-matching filter (CDI proxy classes do not carry {@code @PreMatching}).
     *       The filter is instead registered via {@link PreMatchingJwtAuthDelegate}.</li>
     *   <li>Intercepts {@code getBean(JsonWebToken.class)} to directly resolve the current token
     *       from {@link io.vidocq.cervantes.cdi.JsonWebTokenContext}, bypassing a Vauban bug
     * where the client proxy for a {@code @RequestScoped} producer-method bean is typed
     *       after the DECLARING class ({@code JsonWebTokenProducer_ClientProxy}) instead of
     * the PRODUCTED type ({@code JsonWebToken}) — making the cast fail at injection.
     *       See Vauban {@code InterceptorBeanWrapper.getOrCreateProxy()} for the root cause.</li>
     * </ol>
     */
    private static final class CervantesHarnessBeanProvider
            implements io.vidocq.cassini.spi.bean.BeanProvider {
        private final VaubanBeanProvider delegate;
        private final io.vidocq.cervantes.cdi.JsonWebTokenContext tokenContext;

        CervantesHarnessBeanProvider(VaubanBeanProvider delegate,
                                     io.vidocq.cervantes.cdi.JsonWebTokenContext tokenContext) {
            this.delegate = delegate;
            this.tokenContext = tokenContext;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getBean(Class<T> type) {
            // Workaround: Vauban's proxy for @RequestScoped producer-method beans is typed
            // after the declaring class (JsonWebTokenProducer) not the produced type (JsonWebToken).
            // Return the current token directly from JsonWebTokenContext instead.
            if (type == org.eclipse.microprofile.jwt.JsonWebToken.class) {
                return (T) tokenContext.current()
                        .orElseGet(io.vidocq.cervantes.internal.DefaultJsonWebToken::anonymous);
            }
            return delegate.getBean(type);
        }

        @Override
        public java.util.Set<Class<?>> getResourceClasses() {
            java.util.Set<Class<?>> all = new java.util.LinkedHashSet<>(delegate.getResourceClasses());
            // Exclude JwtAuthenticationFilter: it is registered separately via
            // PreMatchingJwtAuthDelegate which bears @PreMatching directly.
            all.remove(JwtAuthenticationFilter.class);
            return all;
        }

        @Override
        public Object contextualInstance(Class<?> type, Object bean) {
            //Delegates to VaubanBeanProvider to deproxify @RequestScoped resources
            //(if not @Context SecurityContext remains null in the body of the resource method).
            return delegate.contextualInstance(type, bean);
        }
    }

    /**
     * Handler Chappe stripping the contextPath before delegating to Cassini.
     *
     * <p>Reproduit le pattern {@code ContextStrippingHandler} de
     * {@code CassiniTestHarness} : le router Cassini voit le chemin relatif
     * (e.g. {@code /Token/verify}) and not the absolute path with the context
     * (e.g. {@code /TCKApplication/Token/verify}).</p>
     */
    private record ContextStrippingHandler(String prefix, Handler delegate) implements Handler {
        @Override
        public Response handle(Request request) throws Exception {
            String path = request.path();
            if (path == null) path = "/";
            if (!path.startsWith(prefix)) {
                return Response.builder()
                        .status(StatusCode.NOT_FOUND)
                        .body(Body.empty())
                        .build();
            }
            String stripped = path.substring(prefix.length());
            if (stripped.isEmpty()) stripped = "/";
            final String newPath = stripped;
            final String ctxPrefix = prefix;
            Request remapped = new Request() {
                @Override public HttpMethod method()  { return request.method(); }
                @Override public URI uri()            { return request.uri(); }
                @Override public String path()        { return newPath; }
                @Override public String query()       { return request.query(); }
                @Override public HttpVersion version(){ return request.version(); }
                @Override public Headers headers()    { return request.headers(); }
                @Override public Body body()          { return request.body(); }
                @Override public Map<String, String> pathParams()  { return request.pathParams(); }
                @Override public Map<String, String> queryParams() { return request.queryParams(); }
                @Override public String contextPath() { return ctxPrefix; }
                @Override public String pathInfo()    { return newPath; }
            };
            return delegate.handle(remapped);
        }
    }
}
