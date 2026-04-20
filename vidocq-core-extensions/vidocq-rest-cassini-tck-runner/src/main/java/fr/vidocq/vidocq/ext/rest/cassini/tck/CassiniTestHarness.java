package fr.vidocq.vidocq.ext.rest.cassini.tck;

import fr.vidocq.chappe.api.Server;
import fr.vidocq.vidocq.ext.rest.cassini.internal.CassiniRestBridge;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ExceptionMapperRegistry;
import fr.vidocq.vidocq.ext.rest.cassini.internal.Invoker;
import fr.vidocq.vidocq.ext.rest.cassini.internal.MessageBodyRegistry;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceMethod;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceScanner;
import fr.vidocq.vidocq.ext.rest.cassini.internal.UriRouter;
import fr.vidocq.vidocq.ext.rest.cassini.internal.filter.FilterRegistry;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Harness de test minimal pour monter un runtime Cassini complet devant
 * Chappe sur un port éphémère. Utilisé par l'adaptateur Arquillian TCK
 * ({@code VidocqCassiniDeployableContainer}).
 *
 * <p>Construit :</p>
 * <ul>
 *   <li>un {@link UriRouter} à partir des classes {@code @Path} fournies ;</li>
 *   <li>un {@link Invoker} avec resolver basé sur instanciation par
 *     constructeur sans argument (le TCK déploie des ressources simples) ;</li>
 *   <li>un {@link CassiniRestBridge} et le monte sur un {@link Server}
 *     Chappe local.</li>
 * </ul>
 */
public final class CassiniTestHarness implements AutoCloseable {

    private final Server server;
    private final int port;
    private final String baseUrl;

    private CassiniTestHarness(Server server, int port, String baseUrl) {
        this.server = server;
        this.port = port;
        this.baseUrl = baseUrl;
    }

    public int port() { return port; }
    public String baseUrl() { return baseUrl; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<Class<?>, Object> beans = new HashMap<>();
        private String contextPath = "/";

        public Builder resource(Object instance) {
            beans.put(instance.getClass(), instance);
            return this;
        }

        public Builder resourceClass(Class<?> cls) {
            try {
                beans.put(cls, cls.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("failed to instantiate " + cls, e);
            }
            return this;
        }

        public Builder contextPath(String path) {
            this.contextPath = path == null || path.isEmpty() ? "/" : path;
            return this;
        }

        public CassiniTestHarness start() {
            Class<?>[] classes = beans.keySet().toArray(Class<?>[]::new);
            List<ResourceMethod> routes = ResourceScanner.discover(classes);
            UriRouter router = new UriRouter(routes);
            Invoker invoker = new Invoker(beans::get, new MessageBodyRegistry(),
                    new ExceptionMapperRegistry());
            invoker.setFilters(new FilterRegistry());
            CassiniRestBridge bridge = new CassiniRestBridge(router, invoker);

            RuntimeException last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                int port;
                try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                catch (Exception e) { throw new RuntimeException(e); }
                try {
                    Server server = Server.builder()
                            .host("127.0.0.1").port(port).handler(bridge).build();
                    server.start();
                    String url = "http://127.0.0.1:" + port
                            + ("/".equals(contextPath) ? "" : contextPath);
                    return new CassiniTestHarness(server, port, url);
                } catch (RuntimeException e) { last = e; }
            }
            throw last;
        }
    }

    @Override public void close() {
        try { server.stop(); } catch (RuntimeException ignored) {}
    }
}
