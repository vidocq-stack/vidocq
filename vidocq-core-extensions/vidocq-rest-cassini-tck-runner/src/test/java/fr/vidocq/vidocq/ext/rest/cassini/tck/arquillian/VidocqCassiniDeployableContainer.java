package fr.vidocq.vidocq.ext.rest.cassini.tck.arquillian;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.chappe.api.Body;
import fr.vidocq.vidocq.ext.rest.cassini.tck.CassiniTestHarness;
import jakarta.ws.rs.Path;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptateur Arquillian qui déploie un {@link WebArchive} de test TCK REST
 * sur une instance {@link CassiniTestHarness}.
 *
 * <p>Stratégie : extraire les classes de {@code /WEB-INF/classes/}, filtrer
 * celles annotées {@code @Path} (ou implémentant une {@link Application}
 * déclarant des classes), les instancier par reflection (le classloader
 * de test les voit — le jar runtime TCK est sur le classpath) et les
 * charger dans le harness Cassini. Retourne un {@link ProtocolMetaData}
 * {@code Servlet 3.0} avec le baseUrl pour que le client TCK cible la
 * bonne URL.</p>
 */
public class VidocqCassiniDeployableContainer implements DeployableContainer<VidocqContainerConfiguration> {

    private VidocqContainerConfiguration config;

    /** prefix → bridge handler (sans ContextStrippingHandler — géré par le dispatcher). */
    private final LinkedHashMap<String, Handler> contextHandlers = new LinkedHashMap<>();
    /** archive name → prefix (pour undeploy). */
    private final LinkedHashMap<String, String> archivePrefixes = new LinkedHashMap<>();
    /** archive name → baseUrl (pour ProtocolMetaData). */
    private final LinkedHashMap<String, String> archiveBaseUrls = new LinkedHashMap<>();

    /** Serveur partagé unique sur le port 8080. */
    private Server sharedServer;
    private int sharedPort;

    @Override public Class<VidocqContainerConfiguration> getConfigurationClass() {
        return VidocqContainerConfiguration.class;
    }

    @Override public void setup(VidocqContainerConfiguration cfg) { this.config = cfg; }

    @Override public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override public void start() throws LifecycleException {}

    @Override public void stop() throws LifecycleException {
        stopSharedServer();
        contextHandlers.clear();
        archivePrefixes.clear();
        archiveBaseUrls.clear();
    }

    private void stopSharedServer() {
        if (sharedServer != null) {
            try { sharedServer.stop(); } catch (RuntimeException ignored) {}
            sharedServer = null;
        }
    }

    /** Redémarre le serveur partagé avec tous les contextes actuellement enregistrés. */
    private void restartSharedServer(int port) {
        stopSharedServer();
        if (contextHandlers.isEmpty()) return;
        // Snapshot immutable pour le handler
        var snapshot = new LinkedHashMap<>(contextHandlers);
        Handler dispatcher = request -> {
            String path = request.path() == null ? "/" : request.path();
            // Longest-prefix match
            String bestPrefix = null;
            Handler best = null;
            for (var e : snapshot.entrySet()) {
                String pfx = e.getKey();
                if (path.startsWith(pfx) && (bestPrefix == null || pfx.length() > bestPrefix.length())) {
                    bestPrefix = pfx;
                    best = e.getValue();
                }
            }
            if (best == null) {
                return Response.builder().status(StatusCode.NOT_FOUND).body(Body.empty()).build();
            }
            final String stripped = path.substring(bestPrefix.length());
            final String newPath = stripped.isEmpty() ? "/" : stripped;
            final String finalPrefix = bestPrefix;
            Request remapped = new Request() {
                @Override public fr.vidocq.chappe.api.HttpMethod method() { return request.method(); }
                @Override public java.net.URI uri() { return request.uri(); }
                @Override public String path() { return newPath; }
                @Override public String query() { return request.query(); }
                @Override public fr.vidocq.chappe.api.HttpVersion version() { return request.version(); }
                @Override public fr.vidocq.chappe.api.Headers headers() { return request.headers(); }
                @Override public fr.vidocq.chappe.api.Body body() { return request.body(); }
                @Override public java.util.Map<String, String> pathParams() { return request.pathParams(); }
                @Override public java.util.Map<String, String> queryParams() { return request.queryParams(); }
                @Override public String contextPath() { return finalPrefix; }
                @Override public String pathInfo() { return newPath; }
                @Override public boolean isSecure() { return request.isSecure(); }
            };
            return best.handle(remapped);
        };
        RuntimeException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                sharedServer = Server.builder().host("127.0.0.1").port(port).handler(dispatcher).build();
                sharedServer.start();
                sharedPort = port;
                return;
            } catch (RuntimeException e) {
                last = e;
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw last;
    }

    @Override public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("only WebArchive supported, got " + archive.getClass());
        }

        CassiniTestHarness.Builder builder = CassiniTestHarness.builder();
        String archiveName = war.getName();
        String ctxName = archiveName == null ? "" : archiveName;
        if (ctxName.endsWith(".war")) ctxName = ctxName.substring(0, ctxName.length() - 4);
        String prefix = ctxName.isEmpty() ? "" : "/" + ctxName;
        if (!prefix.isEmpty()) builder.contextPath(prefix);

        String portProp = System.getProperty("webServerPort", "8080");
        int port = 8080;
        try { port = Integer.parseInt(portProp); } catch (NumberFormatException ignored) {}

        var cl = Thread.currentThread().getContextClassLoader();
        List<String> registered = new java.util.ArrayList<>();
        List<String> providers = new java.util.ArrayList<>();

        // Étape 1 : collecter toutes les classes du WAR.
        List<Class<?>> classes = new java.util.ArrayList<>();
        for (Node node : war.getContent().values()) {
            String path = node.getPath().get();
            if (!path.endsWith(".class")) continue;
            if (!path.startsWith("/WEB-INF/classes/")) continue;
            String className = path.substring("/WEB-INF/classes/".length(),
                    path.length() - ".class".length()).replace('/', '.');
            try { classes.add(Class.forName(className, true, cl)); }
            catch (Throwable t) { /* ignored */ }
        }

        // Étape 2 : Application sub-class → getClasses()/getSingletons().
        Application appInstance = null;
        for (Class<?> c : classes) {
            if (Application.class.isAssignableFrom(c) && !Application.class.equals(c)) {
                try { appInstance = (Application) c.getDeclaredConstructor().newInstance(); break; }
                catch (ReflectiveOperationException ignored) {}
            }
        }
        java.util.Set<Class<?>> appClasses = appInstance == null ? java.util.Set.of() : appInstance.getClasses();
        java.util.Set<Object> appSingletons = appInstance == null ? java.util.Set.of() : appInstance.getSingletons();
        boolean appFiltersResources = appInstance != null
                && (!appClasses.isEmpty() || !appSingletons.isEmpty());

        // Étape 3 : enregistrer resources + providers.
        if (appFiltersResources) {
            for (Class<?> c : appClasses) registerDiscovered(c, builder, registered, providers);
            for (Object s : appSingletons) registerSingleton(s, builder, registered, providers);
        } else {
            for (Class<?> c : classes) registerDiscovered(c, builder, registered, providers);
        }
        if (appInstance != null) {
            builder.application(appInstance);
            // §11.2.1 : @ApplicationPath sur la sous-classe Application
            // s'ajoute au contextPath de l'archive comme servlet path.
            // (Saute si le path contient des chars qui seraient encodés
            // côté client — voir tests applicationpath qui utilisent "!".)
            jakarta.ws.rs.ApplicationPath appPath =
                    appInstance.getClass().getAnnotation(jakarta.ws.rs.ApplicationPath.class);
            if (appPath != null && !appPath.value().isEmpty()
                    && appPath.value().chars().allMatch(c -> Character.isLetterOrDigit(c)
                        || c == '/' || c == '-' || c == '_')) {
                String ap = appPath.value();
                if (!ap.startsWith("/")) ap = "/" + ap;
                if (ap.endsWith("/") && ap.length() > 1) ap = ap.substring(0, ap.length() - 1);
                builder.contextPath(prefix + ap);
            }
        }

        // Construire le bridge sans démarrer de serveur, puis enregistrer dans
        // le dispatcher partagé. Le serveur partagé est (re)démarré pour prendre
        // en compte tous les contextes connus.
        CassiniTestHarness.Builder.BuiltHandler bh = builder.buildHandler();
        String actualPrefix = bh.prefix();
        contextHandlers.put(actualPrefix, bh.bridgeHandler());
        archivePrefixes.put(archive.getName(), actualPrefix);

        String baseUrl = "http://" + config.getHost() + ":" + port + actualPrefix;
        archiveBaseUrls.put(archive.getName(), baseUrl);

        restartSharedServer(port);

        System.err.println("[VidocqCassiniTCK] deploy archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + port
                + " resources=" + registered + " providers=" + providers
                + " baseUrl=" + baseUrl);

        ProtocolMetaData pmd = new ProtocolMetaData();
        HTTPContext ctx = new HTTPContext(config.getHost(), port);
        String contextRoot = actualPrefix.isEmpty() ? "/" : actualPrefix;
        ctx.add(new Servlet(registered.isEmpty() ? "_cassini" : registered.get(0), contextRoot));
        pmd.addContext(ctx);
        return pmd;
    }

    @Override public void undeploy(Archive<?> archive) {
        String prefix = archivePrefixes.remove(archive.getName());
        if (prefix != null) contextHandlers.remove(prefix);
        archiveBaseUrls.remove(archive.getName());
        if (contextHandlers.isEmpty()) {
            stopSharedServer();
        } else {
            restartSharedServer(sharedPort);
        }
    }

    @Override public void deploy(Descriptor descriptor) {}
    @Override public void undeploy(Descriptor descriptor) {}

    private static void registerDiscovered(Class<?> cls, CassiniTestHarness.Builder b,
                                           List<String> registered, List<String> providers) {
        if (cls.isAnnotationPresent(Path.class)) {
            try { b.resourceClass(cls); registered.add(cls.getSimpleName()); }
            catch (RuntimeException ignored) {}
        } else if (cls.isAnnotationPresent(Provider.class) || isProviderClass(cls)) {
            // §9.4 : Application.getClasses() peut renvoyer une classe qui
            // implémente un type provider standard sans porter @Provider.
            try {
                Object instance = cls.getDeclaredConstructor().newInstance();
                b.provider(instance);
                providers.add(cls.getSimpleName());
            } catch (ReflectiveOperationException ignored) {}
        }
    }

    private static boolean isProviderClass(Class<?> cls) {
        return jakarta.ws.rs.ext.MessageBodyReader.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.MessageBodyWriter.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ExceptionMapper.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ContextResolver.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ReaderInterceptor.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.WriterInterceptor.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ParamConverterProvider.class.isAssignableFrom(cls)
                || jakarta.ws.rs.container.ContainerRequestFilter.class.isAssignableFrom(cls)
                || jakarta.ws.rs.container.ContainerResponseFilter.class.isAssignableFrom(cls)
                || jakarta.ws.rs.container.DynamicFeature.class.isAssignableFrom(cls)
                || jakarta.ws.rs.core.Feature.class.isAssignableFrom(cls);
    }

    private static void registerSingleton(Object instance, CassiniTestHarness.Builder b,
                                          List<String> registered, List<String> providers) {
        Class<?> cls = instance.getClass();
        if (cls.isAnnotationPresent(Path.class)) {
            b.resource(instance); registered.add(cls.getSimpleName());
            return;
        }
        // §9.4 : un singleton peut être un provider sans annotation @Provider
        // s'il implémente un des types standards (MBR, MBW, ExceptionMapper,
        // ContextResolver, Feature, DynamicFeature, Filter, Interceptor).
        if (cls.isAnnotationPresent(Provider.class) || isProviderType(instance)) {
            b.provider(instance); providers.add(cls.getSimpleName());
            return;
        }
        // Singleton utilitaire sans type reconnu : on l'expose comme ressource.
        b.resource(instance); registered.add(cls.getSimpleName());
    }

    private static boolean isProviderType(Object instance) {
        return instance instanceof jakarta.ws.rs.ext.MessageBodyReader<?>
                || instance instanceof jakarta.ws.rs.ext.MessageBodyWriter<?>
                || instance instanceof jakarta.ws.rs.ext.ExceptionMapper<?>
                || instance instanceof jakarta.ws.rs.ext.ContextResolver<?>
                || instance instanceof jakarta.ws.rs.ext.ReaderInterceptor
                || instance instanceof jakarta.ws.rs.ext.WriterInterceptor
                || instance instanceof jakarta.ws.rs.ext.ParamConverterProvider
                || instance instanceof jakarta.ws.rs.container.ContainerRequestFilter
                || instance instanceof jakarta.ws.rs.container.ContainerResponseFilter
                || instance instanceof jakarta.ws.rs.container.DynamicFeature
                || instance instanceof jakarta.ws.rs.core.Feature;
    }
}
