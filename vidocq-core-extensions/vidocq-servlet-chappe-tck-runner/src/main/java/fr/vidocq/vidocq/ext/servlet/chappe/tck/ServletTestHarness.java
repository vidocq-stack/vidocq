package fr.vidocq.vidocq.ext.servlet.chappe.tck;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.vidocq.ext.servlet.chappe.bridge.ChappeServletBridge;
import fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.FilterMapping;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.FilterRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.ServletDispatcher;
import fr.vidocq.vidocq.ext.servlet.chappe.error.ErrorPageRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.listener.ListenerRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.security.SecurityProvider;
import fr.vidocq.vidocq.ext.servlet.chappe.session.InMemorySessionStore;
import fr.vidocq.vidocq.ext.servlet.chappe.session.SessionManager;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

/**
 * Harness de conformance Servlet 6.1 : démarre un {@link Server} Chappe local
 * en branchant un {@link ChappeServletBridge} entièrement configuré, et expose
 * un {@link HttpClient} pour que les tests fassent des requêtes HTTP réelles.
 *
 * <p>Ce harness joue le rôle d'un conteneur servlet pour une suite de tests de
 * conformance. L'intégration d'un TCK officiel (Arquillian DeployableContainer)
 * s'appuiera sur ce même point d'accrochage.</p>
 *
 * <h2>Exemple</h2>
 * <pre>{@code
 * var harness = ServletTestHarness.builder()
 *         .servlet("/hello", new HelloServlet())
 *         .filter("/*", new LoggingFilter())
 *         .start();
 * try (harness) {
 *     HttpResponse<String> r = harness.get("/hello");
 *     assertEquals(200, r.statusCode());
 * }
 * }</pre>
 */
public final class ServletTestHarness implements AutoCloseable {

    private final Server server;
    private final int port;
    private final HttpClient client;
    private final String contextPath;

    private ServletTestHarness(Server server, int port, String contextPath) {
        this.server = server;
        this.port = port;
        this.contextPath = contextPath;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public int port() { return port; }
    public String baseUrl() { return "http://127.0.0.1:" + port + (contextPath.equals("/") ? "" : contextPath); }
    public HttpClient client() { return client; }

    public HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(5)).GET().build());
    }

    public HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(5));
    }

    @Override public void close() {
        if (server != null) server.stop();
    }

    public static Builder builder() { return new Builder(); }

    /** Builder fluide qui configure une application servlet puis démarre Chappe. */
    public static final class Builder {
        private final List<ServletDispatcher.Mapping> servlets = new ArrayList<>();
        private final List<FilterMapping> filters = new ArrayList<>();
        private final List<EventListener> listeners = new ArrayList<>();
        private final ErrorPageRegistry errorPages = new ErrorPageRegistry();
        private String contextPath = "/";
        private SecurityProvider securityProvider;

        public Builder servlet(String urlPattern, jakarta.servlet.Servlet servlet) {
            servlets.add(new ServletDispatcher.Mapping(
                    fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.UrlPatternMatcher.of(urlPattern),
                    servlet, servlet.getClass().getSimpleName()));
            return this;
        }

        public Builder filter(String urlPattern, jakarta.servlet.Filter filter) {
            filters.add(FilterMapping.onRequest(
                    fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.UrlPatternMatcher.of(urlPattern),
                    filter, filter.getClass().getSimpleName()));
            return this;
        }

        public Builder listener(EventListener listener) { listeners.add(listener); return this; }

        public Builder errorPage(int status, String location) {
            errorPages.register(status, location); return this;
        }

        public Builder errorPage(Class<? extends Throwable> type, String location) {
            errorPages.register(type, location); return this;
        }

        public Builder contextPath(String path) { this.contextPath = path; return this; }
        public Builder securityProvider(SecurityProvider p) { this.securityProvider = p; return this; }

        public ServletTestHarness start() {
            VidocqServletContext ctx = new VidocqServletContext(contextPath);
            ctx.setErrorPages(errorPages);
            ListenerRegistry registry = new ListenerRegistry();
            registry.registerAll(listeners);
            ctx.setListenerRegistry(registry);
            if (securityProvider != null) ctx.setSecurityProvider(securityProvider);

            SessionManager sessions = new SessionManager(new InMemorySessionStore(), ctx, 1800);
            sessions.setListenerRegistry(registry);

            var bridge = new ChappeServletBridge(new ServletDispatcher(servlets),
                    new FilterRegistry(filters), ctx, sessions, contextPath);

            registry.fireContextInitialized(ctx);
            int port = startServerWithRetry(bridge);
            return new ServletTestHarness(currentServer, port, contextPath);
        }

        private Server currentServer;

        private int startServerWithRetry(Handler handler) {
            RuntimeException last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                int port;
                try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                catch (Exception e) { throw new RuntimeException(e); }
                try {
                    Server server = Server.builder().host("127.0.0.1").port(port).handler(handler).build();
                    server.start();
                    currentServer = server;
                    return port;
                } catch (RuntimeException e) { last = e; }
            }
            throw last;
        }
    }
}
