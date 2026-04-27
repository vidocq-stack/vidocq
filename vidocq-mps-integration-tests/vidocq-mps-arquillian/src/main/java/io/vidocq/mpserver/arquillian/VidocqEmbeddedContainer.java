package io.vidocq.mpserver.arquillian;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.mpserver.core.VidocqBootstrap;
import io.vidocq.mpserver.spi.VidocqExtension;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.impl.base.path.BasicPath;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Container Arquillian embarque pour Vidocq.
 * <p>
 * Demarre un serveur Vidocq complet (CDI + extensions) avec les classes
 * du deployment ShrinkWrap comme beans CDI.
 * </p>
 */
public class VidocqEmbeddedContainer implements DeployableContainer<VidocqContainerConfig> {

    private VidocqContainerConfig config;
    private VidocqBootstrap bootstrap;
    private int actualPort;

    @Override
    public Class<VidocqContainerConfig> getConfigurationClass() {
        return VidocqContainerConfig.class;
    }

    @Override
    public void setup(VidocqContainerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws LifecycleException {
        // Port allocation done at deploy time
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
            // Resolve port
            actualPort = config.getPort() == 0 ? findFreePort() : config.getPort();

            // Extract bean classes from archive
            List<String> beanClassNames = extractClassNames(archive);

            // Set system properties for REST extension
            System.setProperty("vidocq.rest.host", config.getHost());
            System.setProperty("vidocq.rest.port", String.valueOf(actualPort));

            // Boot Vidocq with extracted classes
            bootstrap = VidocqBootstrap.create();
            bootstrap.configure(beanClassNames);
            bootstrap.start();

            // Return protocol metadata with HTTP context
            HTTPContext httpContext = new HTTPContext(config.getHost(), actualPort);
            httpContext.add(new Servlet("default", "/"));
            ProtocolMetaData metadata = new ProtocolMetaData();
            metadata.addContext(httpContext);
            return metadata;

        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy Vidocq application", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        System.clearProperty("vidocq.rest.host");
        System.clearProperty("vidocq.rest.port");
    }

    private List<String> extractClassNames(Archive<?> archive) {
        List<String> classNames = new ArrayList<>();
        Map<org.jboss.shrinkwrap.api.ArchivePath, Node> content = archive.getContent();
        for (var entry : content.entrySet()) {
            String path = entry.getKey().get();
            if (path.endsWith(".class") && !path.contains("module-info")) {
                // /com/example/MyClass.class -> com.example.MyClass
                String className = path
                        .substring(1) // remove leading /
                        .replace('/', '.')
                        .replace(".class", "");
                classNames.add(className);
            }
        }
        return classNames;
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 18080; // fallback
        }
    }
}
