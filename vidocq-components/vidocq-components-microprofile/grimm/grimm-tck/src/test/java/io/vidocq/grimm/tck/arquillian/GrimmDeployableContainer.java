package io.vidocq.grimm.tck.arquillian;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedded Arquillian container for Grimm OpenAPI TCK runs.
 *
 * It is a focused replacement for the generic runtime container, with a corrected
 * class-name extraction for ShrinkWrap web archives (WEB-INF/classes prefix).
 */
public class GrimmDeployableContainer implements DeployableContainer<GrimmContainerConfiguration> {

    private GrimmContainerConfiguration config;
    private VidocqBootstrap bootstrap;
    private int actualPort;

    @Override
    public Class<GrimmContainerConfiguration> getConfigurationClass() {
        return GrimmContainerConfiguration.class;
    }

    @Override
    public void setup(GrimmContainerConfiguration configuration) {
        this.config = configuration;
    }

    @Override
    public void start() throws LifecycleException {
        // Per-deployment bootstrap happens in deploy().
    }

    @Override
    public void stop() throws LifecycleException {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 6.0");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        try {
            actualPort = config.getPort() == 0 ? findFreePort() : config.getPort();
            List<String> classNames = extractClassNames(archive);

            System.setProperty("vidocq.chappe.listener.default.host", config.getHost());
            System.setProperty("vidocq.chappe.listener.default.port", String.valueOf(actualPort));

            bootstrap = VidocqBootstrap.create();
            bootstrap.configure(classNames);
            bootstrap.start();

            HTTPContext ctx = new HTTPContext(config.getHost(), actualPort);
            ctx.add(new Servlet("default", "/"));
            ProtocolMetaData meta = new ProtocolMetaData();
            meta.addContext(ctx);
            return meta;
        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy Grimm TCK application", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        System.clearProperty("vidocq.chappe.listener.default.host");
        System.clearProperty("vidocq.chappe.listener.default.port");
    }

    @Override
    public void deploy(Descriptor descriptor) {
        // no-op
    }

    @Override
    public void undeploy(Descriptor descriptor) {
        // no-op
    }

    private List<String> extractClassNames(Archive<?> archive) {
        List<String> out = new ArrayList<>();
        Map<ArchivePath, org.jboss.shrinkwrap.api.Node> content = archive.getContent();
        for (Map.Entry<ArchivePath, org.jboss.shrinkwrap.api.Node> e : content.entrySet()) {
            String path = e.getKey().get();
            if (!path.endsWith(".class") || path.contains("module-info")) {
                continue;
            }
            String normalized = path;
            if (normalized.startsWith("/WEB-INF/classes/")) {
                normalized = normalized.substring("/WEB-INF/classes/".length());
            } else if (normalized.startsWith("WEB-INF/classes/")) {
                normalized = normalized.substring("WEB-INF/classes/".length());
            } else if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            String className = normalized.replace('/', '.').replace(".class", "");
            out.add(className);
        }
        return out;
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception ignored) {
            return 18080;
        }
    }
}

