package fr.vidocq.vidocq.ext.rest;

import fr.vidocq.vauban.core.container.VaubanContainerBuilder;
import fr.vidocq.vidocq.spi.ExtensionContext;
import fr.vidocq.vidocq.spi.VidocqConfiguration;
import fr.vidocq.vidocq.spi.VidocqExtension;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;

/**
 * Extension REST pour Vidocq.
 * <p>
 * Intègre Jersey (JAX-RS) avec un serveur HTTP Grizzly embarqué,
 * ponté avec le container CDI Vauban.
 * </p>
 *
 * <p><b>REST extension for Vidocq.</b>
 * Integrates Jersey (JAX-RS) with an embedded Grizzly HTTP server,
 * bridged with the Vauban CDI container.</p>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code vidocq.rest.host} — hôte d'écoute (défaut: {@code 0.0.0.0})</li>
 *   <li>{@code vidocq.rest.port} — port d'écoute (défaut: {@code 8080})</li>
 * </ul>
 */
public class VidocqRestExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(VidocqRestExtension.class.getName());

    private String host = "0.0.0.0";
    private int port = 8080;
    private EmbeddedServer server;

    @Override
    public String name() {
        return "rest";
    }

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.host = config.property("vidocq.rest.host", "0.0.0.0");
        this.port = config.portFor("rest", 8080);
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        // Les ressources JAX-RS seront découvertes via le BeanManager
        // après le boot CDI — rien à enregistrer ici pour l'instant.
    }

    @Override
    public void onStart(ExtensionContext context) {
        LOG.log(System.Logger.Level.INFO, "Starting REST extension on " + host + ":" + port);

        var bridge = new JerseyBridge(context.beanManager());
        var resourceConfig = bridge.configure();

        // Log registered endpoints
        for (Resource resource : resourceConfig.getResources()) {
            String basePath = resource.getPath();
            for (ResourceMethod method : resource.getResourceMethods()) {
                LOG.log(System.Logger.Level.INFO, "  Endpoint: {0} /{1}",
                        method.getHttpMethod(), basePath);
            }
            for (Resource child : resource.getChildResources()) {
                for (ResourceMethod method : child.getResourceMethods()) {
                    LOG.log(System.Logger.Level.INFO, "  Endpoint: {0} /{1}/{2}",
                            method.getHttpMethod(), basePath, child.getPath());
                }
            }
        }

        this.server = new EmbeddedServer();
        server.start(host, port, resourceConfig);

        LOG.log(System.Logger.Level.INFO, "REST extension started: http://" + host + ":" + port + "/");
    }

    @Override
    public void onStop() {
        if (server != null) {
            LOG.log(System.Logger.Level.INFO, "Stopping REST extension");
            server.stop();
        }
    }
}
