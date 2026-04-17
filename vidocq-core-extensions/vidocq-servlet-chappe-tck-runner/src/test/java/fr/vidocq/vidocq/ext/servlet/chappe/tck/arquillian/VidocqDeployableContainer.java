package fr.vidocq.vidocq.ext.servlet.chappe.tck.arquillian;

import fr.vidocq.vidocq.ext.servlet.chappe.tck.ServletTestHarness;
import fr.vidocq.vidocq.ext.servlet.chappe.webxml.WebAppDescriptor;
import fr.vidocq.vidocq.ext.servlet.chappe.webxml.WebXmlParser;
import jakarta.servlet.Filter;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.deployment.Validate;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

/**
 * {@link DeployableContainer} Arquillian qui déploie un {@link WebArchive} sur le
 * {@link ServletTestHarness} interne.
 *
 * <p>Stratégie MVP : extraire les classes du {@code WEB-INF/classes/} de l'archive,
 * les instancier par reflection (le classloader courant les connaît puisque le jar TCK
 * est sur le classpath de test), les classer par annotation {@code @WebServlet/@WebFilter/@WebListener}
 * et les enregistrer dans le harness. Retourne un {@link ProtocolMetaData} {@code Servlet 3.0}
 * avec l'URL du harness pour qu'Arquillian injecte {@code @ArquillianResource URL url}.</p>
 */
public class VidocqDeployableContainer implements DeployableContainer<VidocqContainerConfiguration> {

    private VidocqContainerConfiguration config;
    private ServletTestHarness harness;

    @Override
    public Class<VidocqContainerConfiguration> getConfigurationClass() {
        return VidocqContainerConfiguration.class;
    }

    @Override
    public void setup(VidocqContainerConfiguration cfg) {
        this.config = cfg;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override
    public void start() throws LifecycleException {
        // Harness déploiement-par-déploiement : démarré dans deploy(), arrêté dans undeploy().
    }

    @Override
    public void stop() throws LifecycleException {
        if (harness != null) { harness.close(); harness = null; }
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        Validate.notNull(archive, "archive");
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("only WebArchive supported, got " + archive.getClass());
        }

        if (harness != null) harness.close();
        var builder = ServletTestHarness.builder();
        var cl = Thread.currentThread().getContextClassLoader();
        List<String> registered = new ArrayList<>();

        // 1) Classes @WebServlet/@WebFilter/@WebListener dans /WEB-INF/classes/
        for (Node node : flatten(war).values()) {
            String path = node.getPath().get();
            if (!path.endsWith(".class")) continue;
            if (!path.startsWith("/WEB-INF/classes/")) continue;
            String className = path
                    .substring("/WEB-INF/classes/".length(), path.length() - ".class".length())
                    .replace('/', '.');
            Class<?> cls;
            try { cls = Class.forName(className, true, cl); }
            catch (Throwable t) { continue; }
            registerIfAnnotated(builder, cls, registered);
        }

        // 2) web.xml : enregistre les servlets/filters/listeners déclarés
        Node webXml = war.get("/WEB-INF/web.xml");
        if (webXml != null && webXml.getAsset() != null) {
            try (var in = webXml.getAsset().openStream()) {
                WebAppDescriptor desc = WebXmlParser.parse(in);
                registerFromWebXml(builder, desc, cl, registered);
            } catch (Exception e) {
                System.err.println("[VidocqTCK] failed to parse web.xml: " + e);
            }
        }

        harness = builder.start();

        System.err.println("[VidocqTCK] deploy archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + harness.port()
                + " servlets=" + registered + " baseUrl=" + harness.baseUrl());

        ProtocolMetaData pmd = new ProtocolMetaData();
        var ctx = new HTTPContext(config.getHost(), harness.port());
        // Enregistre au moins un servlet pour que la URL soit résolue par le protocole Servlet 3.0.
        ctx.add(new org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet(
                registered.isEmpty() ? "_vidocq" : registered.get(0), "/"));
        pmd.addContext(ctx);
        return pmd;
    }

    private static void registerFromWebXml(ServletTestHarness.Builder builder,
                                           WebAppDescriptor desc, ClassLoader cl,
                                           List<String> registered) {
        var instances = new java.util.HashMap<String, jakarta.servlet.Servlet>();
        var servletParams = new java.util.HashMap<String, java.util.Map<String, String>>();
        for (WebAppDescriptor.ServletDef sd : desc.servlets()) {
            if (sd.className() == null) continue;
            try {
                Class<?> c = Class.forName(sd.className(), true, cl);
                if (!jakarta.servlet.Servlet.class.isAssignableFrom(c)) continue;
                jakarta.servlet.Servlet s = (jakarta.servlet.Servlet) c.getDeclaredConstructor().newInstance();
                instances.put(sd.name(), s);
                servletParams.put(sd.name(),
                        sd.initParams() == null ? java.util.Map.of() : sd.initParams());
            } catch (ReflectiveOperationException ignored) {}
        }
        for (WebAppDescriptor.ServletMappingDef m : desc.servletMappings()) {
            jakarta.servlet.Servlet s = instances.get(m.servletName());
            if (s != null) {
                builder.servlet(m.urlPattern(), s,
                        servletParams.getOrDefault(m.servletName(), java.util.Map.of()));
                registered.add(m.servletName());
            }
        }
        var filterInstances = new java.util.HashMap<String, jakarta.servlet.Filter>();
        var filterParams = new java.util.HashMap<String, java.util.Map<String, String>>();
        for (WebAppDescriptor.FilterDef fd : desc.filters()) {
            try {
                Class<?> c = Class.forName(fd.className(), true, cl);
                if (!jakarta.servlet.Filter.class.isAssignableFrom(c)) continue;
                jakarta.servlet.Filter f = (jakarta.servlet.Filter) c.getDeclaredConstructor().newInstance();
                filterInstances.put(fd.name(), f);
                filterParams.put(fd.name(),
                        fd.initParams() == null ? java.util.Map.of() : fd.initParams());
            } catch (ReflectiveOperationException ignored) {}
        }
        for (WebAppDescriptor.FilterMappingDef m : desc.filterMappings()) {
            jakarta.servlet.Filter f = filterInstances.get(m.filterName());
            if (f == null) continue;
            var params = filterParams.getOrDefault(m.filterName(), java.util.Map.of());
            if (m.urlPattern() != null) {
                builder.filter(m.urlPattern(), f, m.filterName(), params);
            } else if (m.servletName() != null) {
                // Résout le servlet-name en ses url-patterns via servletMappings
                for (String pattern : desc.patternsFor(m.servletName())) {
                    builder.filter(pattern, f, m.filterName(), params);
                }
            }
        }
        for (String lc : desc.listenerClasses()) {
            try {
                Class<?> c = Class.forName(lc, true, cl);
                if (!java.util.EventListener.class.isAssignableFrom(c)) continue;
                builder.listener((java.util.EventListener) c.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerIfAnnotated(ServletTestHarness.Builder builder, Class<?> cls,
                                            List<String> registered) {
        WebServlet ws = cls.getAnnotation(WebServlet.class);
        if (ws != null && jakarta.servlet.Servlet.class.isAssignableFrom(cls)) {
            try {
                jakarta.servlet.Servlet s = (jakarta.servlet.Servlet) cls.getDeclaredConstructor().newInstance();
                String[] patterns = ws.urlPatterns().length > 0 ? ws.urlPatterns() : ws.value();
                for (String p : patterns) builder.servlet(p, s);
                registered.add(cls.getSimpleName());
            } catch (ReflectiveOperationException ignored) {}
            return;
        }
        WebFilter wf = cls.getAnnotation(WebFilter.class);
        if (wf != null && Filter.class.isAssignableFrom(cls)) {
            try {
                Filter f = (Filter) cls.getDeclaredConstructor().newInstance();
                String[] patterns = wf.urlPatterns().length > 0 ? wf.urlPatterns() : wf.value();
                for (String p : patterns) builder.filter(p, f);
            } catch (ReflectiveOperationException ignored) {}
            return;
        }
        if (cls.isAnnotationPresent(WebListener.class) && EventListener.class.isAssignableFrom(cls)) {
            try {
                EventListener l = (EventListener) cls.getDeclaredConstructor().newInstance();
                builder.listener(l);
            } catch (ReflectiveOperationException ignored) {}
        }
    }

    private static java.util.Map<org.jboss.shrinkwrap.api.ArchivePath, Node> flatten(WebArchive war) {
        return war.getContent();
    }

    @Override
    public void undeploy(Archive<?> archive) {
        if (harness != null) { harness.close(); harness = null; }
    }

    @Override public void deploy(Descriptor descriptor) {}
    @Override public void undeploy(Descriptor descriptor) {}
}
