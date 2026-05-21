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
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * {@link ContainerRequestFilter} qui démarre un span SERVER pour chaque requête
 * HTTP entrante, après extraction du {@code traceparent} W3C éventuel.
 *
 * <p>Conventions OTel HTTP semantic conventions 1.27+ :</p>
 * <ul>
 *   <li>{@code http.request.method} (GET/POST/...)</li>
 *   <li>{@code url.path}</li>
 *   <li>{@code url.scheme}</li>
 * </ul>
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
    static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    static final AttributeKey<String> URL_SCHEME = AttributeKey.stringKey("url.scheme");

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
        // UriInfo.getPath() retourne le path relatif au base URI (sans '/' initial) selon
        // la spec JAX-RS. La convention OTel HTTP semantic exige url.path avec '/' initial.
        String rawPath = requestContext.getUriInfo().getPath();
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        String scheme = requestContext.getUriInfo().getRequestUri().getScheme();
        String spanName = method + " " + path;

        SpanBuilder builder = tracer().spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .setAttribute(HTTP_REQUEST_METHOD, method)
                .setAttribute(URL_PATH, path);
        if (scheme != null) builder.setAttribute(URL_SCHEME, scheme);

        Span span = builder.startSpan();
        Scope scope = span.makeCurrent();
        requestContext.setProperty(SPAN_PROPERTY, span);
        requestContext.setProperty(SCOPE_PROPERTY, scope);
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
