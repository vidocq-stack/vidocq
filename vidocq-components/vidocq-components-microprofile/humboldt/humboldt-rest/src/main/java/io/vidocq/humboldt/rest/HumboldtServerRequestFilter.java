package io.vidocq.humboldt.rest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;

/**
 * {@link ContainerRequestFilter} qui démarre un span SERVER pour chaque requête
 * HTTP entrante, après extraction du {@code traceparent} W3C éventuel.
 *
 * <p>Conventions OTel HTTP semantic conventions 1.27+ posées sur le span :</p>
 * <ul>
 *   <li>{@code http.request.method} — GET/POST/...</li>
 *   <li>{@code http.route} — template paramétré (ex. {@code /users/{id}}),
 *       reconstruit depuis {@link UriInfo#getMatchedTemplates()} et le base path</li>
 *   <li>{@code url.path} — path complet incluant le context root, sans query string</li>
 *   <li>{@code url.query} — query string sans le {@code ?} initial (si présent)</li>
 *   <li>{@code url.scheme} — http/https</li>
 *   <li>{@code server.address} — host du request URI</li>
 *   <li>{@code server.port} — port du request URI (omis si port standard du scheme)</li>
 * </ul>
 *
 * <p>Nom du span : {@code "<METHOD> <route>"} (e.g., {@code "GET /users/{id}"}),
 * conformément à la convention OTel HTTP server semconv.</p>
 *
 * <p>Le {@link Span} créé est stocké dans la propriété {@link #SPAN_PROPERTY}
 * du {@link ContainerRequestContext} pour récupération par
 * {@link HumboldtServerResponseFilter}. Le {@link Scope} associé est stocké
 * dans {@link #SCOPE_PROPERTY} pour fermeture symétrique.</p>
 */
@Provider
public class HumboldtServerRequestFilter implements ContainerRequestFilter {

    public static final String SPAN_PROPERTY = "io.vidocq.humboldt.rest.span";
    public static final String SCOPE_PROPERTY = "io.vidocq.humboldt.rest.scope";

    static final AttributeKey<String> HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    static final AttributeKey<String> URL_QUERY = AttributeKey.stringKey("url.query");
    static final AttributeKey<String> URL_SCHEME = AttributeKey.stringKey("url.scheme");
    static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");

    /**
     * Injecté par JAX-RS pour récupérer la classe et la méthode de la ressource matchée
     * (post-matching uniquement — ce filter n'est pas {@code @PreMatching}).
     * Permet de reconstruire le {@code http.route} via introspection des {@code @Path}.
     */
    @jakarta.ws.rs.core.Context
    ResourceInfo resourceInfo;

    private static final TextMapGetter<ContainerRequestContext> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(ContainerRequestContext carrier) {
            return carrier.getHeaders().keySet();
        }

        @Override
        public String get(ContainerRequestContext carrier, String key) {
            if (carrier == null) return null;
            List<String> values = carrier.getHeaders().get(key);
            return (values == null || values.isEmpty()) ? null : values.getFirst();
        }
    };

    @Override
    public void filter(ContainerRequestContext requestContext) {
        TextMapPropagator propagator = propagators().textMap();
        Context parent = propagator.extract(Context.current(), requestContext, HEADER_GETTER);

        String method = requestContext.getMethod();
        UriInfo uriInfo = requestContext.getUriInfo();
        URI requestUri = uriInfo.getRequestUri();
        URI baseUri = uriInfo.getBaseUri();

        // url.path doit inclure le context root (base path) car la spec OTel HTTP attend
        // le path complet vu par le client. Certains stacks (dont Cassini in-process)
        // renvoient un requestUri stripped du context — on reconstruit depuis baseUri + getPath().
        String urlPath = buildFullPath(uriInfo);
        String urlQuery = requestUri.getRawQuery(); // null si absent
        String scheme = (baseUri != null) ? baseUri.getScheme() : requestUri.getScheme();
        String serverAddress = (baseUri != null) ? baseUri.getHost() : requestUri.getHost();
        int serverPort = (baseUri != null) ? baseUri.getPort() : requestUri.getPort();
        String route = buildRoute(uriInfo, resourceInfo);
        if (route == null) route = urlPath; // fallback non-templaté quand ResourceInfo absent
        String spanName = method + " " + route;

        SpanBuilder builder = tracer().spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .setAttribute(HTTP_REQUEST_METHOD, method)
                .setAttribute(URL_PATH, urlPath);
        if (urlQuery != null) builder.setAttribute(URL_QUERY, urlQuery);
        if (scheme != null) builder.setAttribute(URL_SCHEME, scheme);
        if (route != null) builder.setAttribute(HTTP_ROUTE, route);
        if (serverAddress != null) builder.setAttribute(SERVER_ADDRESS, serverAddress);
        if (serverPort > 0 && !isDefaultPortForScheme(serverPort, scheme)) {
            builder.setAttribute(SERVER_PORT, (long) serverPort);
        }

        Span span = builder.startSpan();
        Scope scope = span.makeCurrent();
        requestContext.setProperty(SPAN_PROPERTY, span);
        requestContext.setProperty(SCOPE_PROPERTY, scope);
    }

    /**
     * Reconstruit le {@code http.route} à partir des annotations {@link Path} de la
     * resource class et de la resource method matchées (via {@link ResourceInfo}),
     * préfixées par le base path du déploiement.
     * <p>
     * Exemple : classe {@code @Path("/parent")} + méthode {@code @Path("/{id}")},
     * appel {@code /context-root/parent/123} → route = {@code "/context-root/parent/{id}"}.
     * <p>
     * Note : la spec JAX-RS standard n'expose pas {@code getMatchedTemplates()}
     * (uniquement présent dans RestEasy), d'où l'introspection manuelle.
     *
     * @return le route templaté, ou {@code null} si aucune ressource n'a été matchée
     *         (filter pré-matching ou erreur 404).
     */
    private static String buildRoute(UriInfo uriInfo, ResourceInfo resourceInfo) {
        if (resourceInfo == null) return null;
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Method resourceMethod = resourceInfo.getResourceMethod();
        if (resourceClass == null && resourceMethod == null) return null;

        String base = uriInfo.getBaseUri().getRawPath();
        if (base == null) base = "/";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        StringBuilder sb = new StringBuilder(base);
        if (resourceClass != null) {
            Path classPath = resourceClass.getAnnotation(Path.class);
            if (classPath != null) appendPathSegment(sb, classPath.value());
        }
        if (resourceMethod != null) {
            Path methodPath = resourceMethod.getAnnotation(Path.class);
            if (methodPath != null) appendPathSegment(sb, methodPath.value());
        }
        return sb.toString();
    }

    private static void appendPathSegment(StringBuilder sb, String segment) {
        if (segment == null || segment.isEmpty()) return;
        if (!segment.startsWith("/")) sb.append('/');
        sb.append(segment);
    }

    /**
     * Reconstruit le path absolu vu par le client : {@code baseUri.path + uriInfo.path}.
     * <p>
     * Évite la perte du context root sur certains stacks JAX-RS in-process qui renvoient
     * un {@code requestUri} déjà stripped (Cassini, Helidon embedded).
     */
    private static String buildFullPath(UriInfo uriInfo) {
        String relative = uriInfo.getPath(); // sans slash initial
        URI base = uriInfo.getBaseUri();
        String basePath = (base != null) ? base.getRawPath() : "/";
        if (basePath == null || basePath.isEmpty()) basePath = "/";
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        if (relative == null || relative.isEmpty()) {
            return basePath.isEmpty() ? "/" : basePath;
        }
        if (relative.startsWith("/")) return basePath + relative;
        return basePath + "/" + relative;
    }

    private static boolean isDefaultPortForScheme(int port, String scheme) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    protected Tracer tracer() {
        return openTelemetry().getTracer("io.vidocq.humboldt.rest");
    }

    protected OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    protected io.opentelemetry.context.propagation.ContextPropagators propagatorsAdapter() {
        return W3CPropagators.get();
    }

    /**
     * Hook test — surchargeable pour pointer un propagator custom.
     * @return l'adapteur W3C par défaut.
     */
    private W3CPropagatorsWrapper propagators() {
        return new W3CPropagatorsWrapper();
    }

    /** Wrapper interne — exposition de {@code textMap()} de manière testable. */
    static class W3CPropagatorsWrapper {
        TextMapPropagator textMap() {
            return W3CPropagators.textMap();
        }
    }
}
