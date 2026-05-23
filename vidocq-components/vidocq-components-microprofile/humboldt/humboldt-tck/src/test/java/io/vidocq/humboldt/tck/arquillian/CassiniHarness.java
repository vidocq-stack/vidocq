package io.vidocq.humboldt.tck.arquillian;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.internal.DefaultCassiniHttpAdapter;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harness minimaliste Cassini sur Chappe — version simplifiée du
 * {@code CassiniTestHarness} de cassini-tck, adaptée pour le runner
 * Arquillian Humboldt.
 *
 * <p>Démarre un serveur Chappe sur un port libre, monte un dispatcher
 * Cassini sur les classes {@code @Path}/{@code @Provider} fournies, et
 * expose le {@code baseUrl} pour {@code @ArquillianResource URL url}.</p>
 *
 * <p>Pas de réutilisation directe de cassini-tck (hors-reactor + pas
 * installé en M2 local). Code aligné mais réduit au strict nécessaire
 * pour les TCK MP Telemetry HTTP.</p>
 */
public final class CassiniHarness implements AutoCloseable {

    private final Server server;
    private final int port;
    private final String baseUrl;

    private CassiniHarness(Server server, int port, String baseUrl) {
        this.server = server;
        this.port = port;
        this.baseUrl = baseUrl;
    }

    public int port() { return port; }
    public String baseUrl() { return baseUrl; }

    @Override public void close() {
        try { server.stop(); } catch (RuntimeException ignored) {}
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<Class<?>, Object> beans = new HashMap<>();
        private final Set<Class<?>> perRequestClasses = new LinkedHashSet<>();
        private final FilterRegistry filters = new FilterRegistry();
        private final ExceptionMapperRegistry exceptionMappers = new ExceptionMapperRegistry();
        private final MessageBodyRegistry bodies = new MessageBodyRegistry();
        private String contextPath = "/";

        public Builder resourceClass(Class<?> cls) {
            if (!java.lang.reflect.Modifier.isPublic(cls.getModifiers())) return this;
            if (java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) return this;
            perRequestClasses.add(cls);
            return this;
        }

        public Builder provider(Object instance) {
            filters.register(instance);
            if (instance instanceof ExceptionMapper<?> em) registerExceptionMapper(em);
            if (instance instanceof MessageBodyReader<?> r) bodies.addReader(r);
            if (instance instanceof MessageBodyWriter<?> w) bodies.addWriter(w);
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

        public Builder contextPath(String path) {
            this.contextPath = path == null || path.isEmpty() ? "/" : path;
            return this;
        }

        public CassiniHarness start() {
            Set<Class<?>> allClasses = new LinkedHashSet<>(beans.keySet());
            allClasses.addAll(perRequestClasses);
            List<ResourceMethod> routes = ResourceScanner.discover(allClasses.toArray(Class<?>[]::new));
            filters.applyDynamicFeatures(routes);
            UriRouter router = new UriRouter(routes);
            // Récupère le container Vauban courant (initialisé par
            // HumboldtDeployableContainer.deploy()) pour passer par CDI lors de la
            // résolution des ressources/providers — assure que les @Inject sur les
            // champs des ressources (Tracer, Span, Baggage, OpenTelemetry...) sont
            // câblés quand la classe est un bean Vauban.
            //
            // Limitation actuelle : la BCE Cassini (cassini-cdi-vauban
            // CassiniScopeExtension) qui ajoute @RequestScoped aux classes @Path sans
            // scope explicite ne s'applique PAS aux classes ajoutées via addBeanClass()
            // en runtime — uniquement aux beans découverts à compile-time via APT.
            // Conséquence : les ressources TCK comme BaggageResource (classe inner sans
            // scope) tombent dans le fallback `new` et leurs @Inject restent null.
            // Fix complet : appliquer les BCE en runtime côté Vauban (chantier séparé)
            // ou pré-traiter les classes @Path dans HumboldtDeployableContainer pour
            // ajouter @RequestScoped synthétique avant addBeanClass().
            io.vidocq.vauban.core.container.VaubanContainer cdi =
                    io.vidocq.vauban.core.container.VaubanContainer.current();
            java.util.function.Function<Class<?>, Object> resolver = cls -> {
                Object fixed = beans.get(cls);
                if (fixed != null) return fixed;
                if (cdi != null) {
                    try {
                        return cdi.select(cls);
                    } catch (RuntimeException ignored) {
                        // Fallback si la classe n'est pas connue de Vauban (BCE non appliquée).
                    }
                }
                try {
                    return cls.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate " + cls, e);
                }
            };
            Invoker invoker = new Invoker(resolver, bodies, exceptionMappers);
            invoker.setFilters(filters);
            DefaultCassiniHttpAdapter engine = new DefaultCassiniHttpAdapter(router, invoker);
            ChappeHttpAdapter bridge = new ChappeHttpAdapter(engine);
            final String prefix = "/".equals(contextPath) ? "" : contextPath;
            Handler stripping = prefix.isEmpty()
                    ? bridge
                    : new ContextStrippingHandler(prefix, bridge);
            // Workaround HBT-1 : active le RequestContext Vauban autour de chaque dispatch.
            // cassini-cdi-vauban ne le fait pas encore (à corriger côté Cassini), donc sans
            // ce wrapper toute resource @RequestScoped (= toute @Path après BCE Cassini)
            // throw ContextNotActiveException.
            Handler rootHandler = cdi != null
                    ? new RequestScopeActivatingHandler(cdi, stripping)
                    : stripping;

            RuntimeException last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                int port;
                try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                catch (Exception e) { throw new RuntimeException(e); }
                try {
                    Server server = Server.builder()
                            .host("127.0.0.1").port(port).handler(rootHandler).build();
                    server.start();
                    String url = "http://127.0.0.1:" + port + (prefix.isEmpty() ? "/" : prefix + "/");
                    return new CassiniHarness(server, port, url);
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            throw last;
        }
    }

    /**
     * Wrapper Handler qui active le {@link io.vidocq.vauban.core.context.RequestContext}
     * autour de chaque dispatch HTTP — workaround pour HBT-1 (cassini-cdi-vauban ne fait
     * pas encore d'activate/deactivate automatique du RequestScope par requête).
     */
    private record RequestScopeActivatingHandler(
            io.vidocq.vauban.core.container.VaubanContainer cdi,
            Handler delegate) implements Handler {
        @Override public Response handle(Request request) throws Exception {
            var rc = cdi.requestContext();
            rc.activate();
            try {
                return delegate.handle(request);
            } finally {
                rc.deactivate();
            }
        }
    }

    /** Strip du contextPath avant délégation au bridge Cassini. */
    private record ContextStrippingHandler(String prefix, Handler delegate) implements Handler {
        @Override public Response handle(Request request) throws Exception {
            String path = request.path();
            if (path == null) path = "/";
            if (!path.startsWith(prefix)) {
                return Response.builder().status(StatusCode.NOT_FOUND).body(Body.empty()).build();
            }
            String stripped = path.substring(prefix.length());
            if (stripped.isEmpty()) stripped = "/";
            final String newPath = stripped;
            Request remapped = new Request() {
                @Override public HttpMethod method() { return request.method(); }
                @Override public URI uri() { return request.uri(); }
                @Override public String path() { return newPath; }
                @Override public String query() { return request.query(); }
                @Override public HttpVersion version() { return request.version(); }
                @Override public Headers headers() { return request.headers(); }
                @Override public Body body() { return request.body(); }
                @Override public Map<String, String> pathParams() { return request.pathParams(); }
                @Override public Map<String, String> queryParams() { return request.queryParams(); }
                @Override public String contextPath() { return prefix; }
                @Override public String pathInfo() { return newPath; }
            };
            return delegate.handle(remapped);
        }
    }
}
