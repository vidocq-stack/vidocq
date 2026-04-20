package fr.vidocq.vidocq.ext.rest.cassini.tck.arquillian;

import fr.vidocq.vidocq.ext.rest.cassini.tck.CassiniTestHarness;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
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

        for (Node node : war.getContent().values()) {
            String path = node.getPath().get();
            if (!path.endsWith(".class")) continue;
            if (!path.startsWith("/WEB-INF/classes/")) continue;
            String className = path.substring("/WEB-INF/classes/".length(),
                    path.length() - ".class".length()).replace('/', '.');
            Class<?> cls;
            try { cls = Class.forName(className, true, cl); }
            catch (Throwable t) { continue; }
            if (cls.isAnnotationPresent(Path.class)) {
                try {
                    builder.resourceClass(cls);
                    registered.add(cls.getSimpleName());
                } catch (RuntimeException ignored) {}
            }
        }

        CassiniTestHarness harness = builder.start();
        harnesses.put(archive.getName(), harness);

        System.err.println("[VidocqCassiniTCK] deploy archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + harness.port()
                + " resources=" + registered + " baseUrl=" + harness.baseUrl());

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
}
