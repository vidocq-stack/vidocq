package fr.vidocq.vidocq.ext.rest.cassini.tck.arquillian;

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
    private final LinkedHashMap<String, CassiniTestHarness> harnesses = new LinkedHashMap<>();

    @Override public Class<VidocqContainerConfiguration> getConfigurationClass() {
        return VidocqContainerConfiguration.class;
    }

    @Override public void setup(VidocqContainerConfiguration cfg) { this.config = cfg; }

    @Override public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override public void start() throws LifecycleException {}

    @Override public void stop() throws LifecycleException {
        for (var h : harnesses.values()) {
            try { h.close(); } catch (RuntimeException ignored) {}
        }
        harnesses.clear();
    }

    @Override public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("only WebArchive supported, got " + archive.getClass());
        }

        CassiniTestHarness.Builder builder = CassiniTestHarness.builder();
        String archiveName = war.getName();
        String ctxName = archiveName == null ? "" : archiveName;
        if (ctxName.endsWith(".war")) ctxName = ctxName.substring(0, ctxName.length() - 4);
        if (!ctxName.isEmpty()) builder.contextPath("/" + ctxName);
        // Fixe le port à celui attendu par le client TCK (webServerPort, défaut 8080).
        // Le TCK lit cette propriété au static init — impossible d'inverser la
        // dépendance. Un seul harness actif à la fois → pas de collision.
        String portProp = System.getProperty("webServerPort", "8080");
        try { builder.port(Integer.parseInt(portProp)); } catch (NumberFormatException ignored) {}

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

        // Étape 2 : si une sous-classe d'Application est présente, l'instancier
        // et respecter getClasses()/getSingletons() (§2.3.2). Sinon, scan libre.
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
        // Expose l'instance Application pour injection @Context Application (§9.4).
        if (appInstance != null) builder.application(appInstance);

        CassiniTestHarness harness = builder.start();
        harnesses.put(archive.getName(), harness);

        System.err.println("[VidocqCassiniTCK] deploy archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + harness.port()
                + " resources=" + registered + " providers=" + providers
                + " baseUrl=" + harness.baseUrl());

        ProtocolMetaData pmd = new ProtocolMetaData();
        HTTPContext ctx = new HTTPContext(config.getHost(), harness.port());
        String contextRoot = harness.baseUrl().substring(
                ("http://" + config.getHost() + ":" + harness.port()).length());
        if (contextRoot.isEmpty()) contextRoot = "/";
        ctx.add(new Servlet(registered.isEmpty() ? "_cassini" : registered.get(0), contextRoot));
        pmd.addContext(ctx);
        return pmd;
    }

    @Override public void undeploy(Archive<?> archive) {
        CassiniTestHarness h = harnesses.remove(archive.getName());
        if (h != null) h.close();
    }

    @Override public void deploy(Descriptor descriptor) {}
    @Override public void undeploy(Descriptor descriptor) {}

    private static void registerDiscovered(Class<?> cls, CassiniTestHarness.Builder b,
                                           List<String> registered, List<String> providers) {
        if (cls.isAnnotationPresent(Path.class)) {
            try { b.resourceClass(cls); registered.add(cls.getSimpleName()); }
            catch (RuntimeException ignored) {}
        } else if (cls.isAnnotationPresent(Provider.class)) {
            try {
                Object instance = cls.getDeclaredConstructor().newInstance();
                b.provider(instance);
                providers.add(cls.getSimpleName());
            } catch (ReflectiveOperationException ignored) {}
        }
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
