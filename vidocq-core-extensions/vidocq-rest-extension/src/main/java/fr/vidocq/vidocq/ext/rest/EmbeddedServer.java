package fr.vidocq.vidocq.ext.rest;

import fr.vidocq.vauban.core.context.RequestContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.glassfish.jersey.servlet.ServletContainer;

import java.io.IOException;
import java.util.EnumSet;

/**
 * Serveur HTTP embarque base sur Jetty 12.
 * <p>
 * Un filtre Servlet enveloppe chaque requete dans
 * {@link RequestContext#runInScope(Runnable)} pour activer le
 * contexte CDI {@code @RequestScoped}.
 * </p>
 */
final class EmbeddedServer {

    private static final System.Logger LOG = System.getLogger(EmbeddedServer.class.getName());

    private Server server;

    void start(String host, int port, org.glassfish.jersey.server.ResourceConfig resourceConfig) {
        server = new Server();

        // Connector
        var connector = new ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        server.addConnector(connector);

        // Servlet context with Jersey
        var context = new ServletContextHandler("/");
        var jerseyServlet = new ServletHolder(new ServletContainer(resourceConfig));
        context.addServlet(jerseyServlet, "/*");

        // CDI request scope filter
        RequestContext requestContext = new RequestContext();
        context.addFilter(new FilterHolder(
                (request, response, chain)
                        -> requestContext.runInScope(() -> {
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException e) {
                throw new RuntimeException(e);
            }
        })), "/*", EnumSet.of(DispatcherType.REQUEST));

        server.setHandler(context);

        try {
            server.start();
            LOG.log(System.Logger.Level.INFO, "Jetty server started on http://" + host + ":" + port + "/");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Jetty on " + host + ":" + port, e);
        }
    }

    void stop() {
        if (server != null) {
            try {
                server.stop();
                LOG.log(System.Logger.Level.INFO, "Jetty server stopped");
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "Error stopping Jetty", e);
            }
        }
    }
}
