package fr.vidocq.vidocq.ext.rest.cassini.tck;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.vidocq.ext.rest.cassini.internal.CassiniRestBridge;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ExceptionMapperRegistry;
import fr.vidocq.vidocq.ext.rest.cassini.internal.Invoker;
import fr.vidocq.vidocq.ext.rest.cassini.internal.MessageBodyRegistry;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceMethod;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceScanner;
import fr.vidocq.vidocq.ext.rest.cassini.internal.UriRouter;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ExceptionMapperRegistry;
import fr.vidocq.vidocq.ext.rest.cassini.internal.filter.FilterRegistry;
import jakarta.ws.rs.ext.ExceptionMapper;

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
        private final FilterRegistry filters = new FilterRegistry();
        private final ExceptionMapperRegistry exceptionMappers = new ExceptionMapperRegistry();
        private String contextPath = "/";
        private Integer fixedPort;

        public Builder provider(Object instance) {
            filters.register(instance);
            if (instance instanceof ExceptionMapper<?> em) {
                registerExceptionMapper(em);
            }
            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void registerExceptionMapper(ExceptionMapper em) {
            for (var iface : em.getClass().getGenericInterfaces()) {
                if (iface instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() == ExceptionMapper.class
                        && pt.getActualTypeArguments().length == 1
                        && pt.getActualTypeArguments()[0] instanceof Class<?> c
                        && Throwable.class.isAssignableFrom(c)) {
                    exceptionMappers.register((Class) c, em);
                    return;
                }
            }
        }

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

        public Builder port(int port) { this.fixedPort = port; return this; }

        public CassiniTestHarness start() {
            Class<?>[] classes = beans.keySet().toArray(Class<?>[]::new);
            List<ResourceMethod> routes = ResourceScanner.discover(classes);
            UriRouter router = new UriRouter(routes);
            Invoker invoker = new Invoker(beans::get, new MessageBodyRegistry(), exceptionMappers);
            invoker.setFilters(filters);
            CassiniRestBridge bridge = new CassiniRestBridge(router, invoker);
            final String prefix = "/".equals(contextPath) ? "" : contextPath;
            Handler rootHandler = prefix.isEmpty() ? bridge : new ContextStrippingHandler(prefix, bridge);

            RuntimeException last = null;
            int attempts = fixedPort != null ? 1 : 5;
            for (int attempt = 0; attempt < attempts; attempt++) {
                int port;
                if (fixedPort != null) {
                    port = fixedPort;
                } else {
                    try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                try {
                    Server server = Server.builder()
                            .host("127.0.0.1").port(port).handler(rootHandler).build();
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

    /**
     * Handler racine qui strippe le contextPath avant de déléguer au bridge
     * Cassini. Reproduit ce que {@code ChappeMountPoint.mount(prefix, ...)}
     * fait dans l'intégration Vidocq normale, mais sans dépendre du moteur
     * complet (le harness embarque juste un Chappe Server nu).
     */
    private record ContextStrippingHandler(String prefix, CassiniRestBridge delegate) implements Handler {
        @Override public Response handle(Request request) throws Exception {
            String path = request.path();
            if (path == null) path = "/";
            if (!path.startsWith(prefix)) {
                // Hors du contexte déployé — 404 direct pour éviter de matcher
                // l'arbre Cassini sur des chemins sans rapport.
                return Response.builder()
                        .status(fr.vidocq.chappe.api.StatusCode.NOT_FOUND)
                        .body(fr.vidocq.chappe.api.Body.empty())
                        .build();
            }
            String stripped = path.substring(prefix.length());
            if (stripped.isEmpty()) stripped = "/";
            final String newPath = stripped;
            Request remapped = new Request() {
                @Override public fr.vidocq.chappe.api.HttpMethod method() { return request.method(); }
                @Override public java.net.URI uri() { return request.uri(); }
                @Override public String path() { return newPath; }
                @Override public String query() { return request.query(); }
                @Override public fr.vidocq.chappe.api.HttpVersion version() { return request.version(); }
                @Override public fr.vidocq.chappe.api.Headers headers() { return request.headers(); }
                @Override public fr.vidocq.chappe.api.Body body() { return request.body(); }
                @Override public java.util.Map<String, String> pathParams() { return request.pathParams(); }
                @Override public java.util.Map<String, String> queryParams() { return request.queryParams(); }
                @Override public String contextPath() { return prefix; }
                @Override public String pathInfo() { return newPath; }
            };
            return delegate.handle(remapped);
        }
    }
}
