package fr.vidocq.vidocq.ext.rest;

import fr.vidocq.vauban.core.context.RequestContext;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.io.IOException;
import java.net.URI;

/**
 * Serveur HTTP embarqué basé sur Grizzly.
 * <p>
 * Wrapper léger autour de {@link GrizzlyHttpServerFactory} pour
 * démarrer/arrêter le serveur HTTP avec une configuration Jersey.
 * </p>
 * <p>
 * Chaque requête HTTP est enveloppée dans {@link RequestContext#runInScope(Runnable)}
 * pour activer le contexte CDI {@code @RequestScoped}.
 * </p>
 */
final class EmbeddedServer {

    private static final System.Logger LOG = System.getLogger(EmbeddedServer.class.getName());

    private HttpServer server;

    void start(String host, int port, ResourceConfig resourceConfig) {
        URI baseUri = URI.create("http://" + host + ":" + port + "/");

        // Create server without starting (false)
        this.server = GrizzlyHttpServerFactory.createHttpServer(baseUri, resourceConfig, false);

        // Wrap the Jersey handler with CDI request scope activation
        var serverConfig = server.getServerConfiguration();
        var handlers = serverConfig.getHttpHandlersWithMapping();

        RequestContext requestContext = new RequestContext();

        for (var entry : handlers.entrySet()) {
            HttpHandler original = entry.getKey();
            var mappings = entry.getValue();

            serverConfig.removeHttpHandler(original);

            HttpHandler wrapped = new HttpHandler() {
                @Override
                public void service(Request request, Response response) throws Exception {
                    requestContext.runInScope(() -> {
                        try {
                            original.service(request, response);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            };

            String[] paths = new String[mappings.length];
            for (int i = 0; i < mappings.length; i++) {
                paths[i] = mappings[i].getContextPath();
            }
            serverConfig.addHttpHandler(wrapped, paths);
        }

        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Grizzly HTTP server on " + baseUri, e);
        }
    }

    void stop() {
        if (server != null) {
            server.shutdownNow();
            LOG.log(System.Logger.Level.INFO, "Grizzly HTTP server stopped");
        }
    }
}
