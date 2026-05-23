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
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.net.URI;

/**
 * {@link ClientRequestFilter} qui démarre un span {@link SpanKind#CLIENT} pour chaque
 * requête HTTP sortante, pose les attributs OTel HTTP semantic conventions 1.27+
 * applicables côté client, et injecte le {@code traceparent} W3C dans les headers
 * sortants pour propagation distribuée.
 *
 * <p>Attributs posés sur le span :</p>
 * <ul>
 *   <li>{@code http.request.method} — GET/POST/...</li>
 *   <li>{@code url.full} — URL complète de la requête (sans fragment)</li>
 *   <li>{@code server.address} — host du request URI</li>
 *   <li>{@code server.port} — port du request URI (omis si port standard du scheme)</li>
 * </ul>
 *
 * <p>Nom du span : {@code "<METHOD>"} (e.g., {@code "GET"}), conformément à la convention
 * OTel HTTP client semconv qui ne template pas la route côté client (l'URL complète
 * suffit ; le pattern n'est connu que côté serveur via {@code @Path}).</p>
 *
 * <p>Le span et le scope sont stockés dans les propriétés du {@link ClientRequestContext}
 * pour récupération symétrique par {@link HumboldtClientResponseFilter} qui les termine.</p>
 *
 * <p>Priorité {@link Priorities#HEADER_DECORATOR} (3000) — s'exécute après les filtres
 * d'authentification (1000) pour que le span englobe le coût des headers auth, mais avant
 * les filtres applicatifs USER (5000) qui pourraient muter l'URI.</p>
 */
@Priority(Priorities.HEADER_DECORATOR)
public class HumboldtClientRequestFilter implements ClientRequestFilter {

    public static final String SPAN_PROPERTY = "io.vidocq.humboldt.rest.client.span";
    public static final String SCOPE_PROPERTY = "io.vidocq.humboldt.rest.client.scope";

    static final AttributeKey<String> HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
    static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");

    /**
     * {@link TextMapSetter} qui écrit les headers W3C ({@code traceparent}, {@code tracestate},
     * {@code baggage}) dans la {@link jakarta.ws.rs.core.MultivaluedMap} mutable du request
     * context. Remplace toute valeur existante (putSingle) pour respecter la sémantique
     * "one trace per request".
     */
    private static final TextMapSetter<ClientRequestContext> HEADER_SETTER = (carrier, key, value) -> {
        if (carrier != null) carrier.getHeaders().putSingle(key, value);
    };

    @Override
    public void filter(ClientRequestContext requestContext) {
        OpenTelemetry otel = openTelemetry();
        Tracer tracer = otel.getTracer("io.vidocq.humboldt.rest.client");
        String method = requestContext.getMethod();
        URI uri = requestContext.getUri();

        SpanBuilder spanBuilder = tracer.spanBuilder(method)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(HTTP_REQUEST_METHOD, method)
                .setAttribute(URL_FULL, sanitize(uri));

        String host = uri.getHost();
        if (host != null) spanBuilder.setAttribute(SERVER_ADDRESS, host);
        int port = uri.getPort();
        if (port > 0 && !isDefaultPort(uri.getScheme(), port)) {
            spanBuilder.setAttribute(SERVER_PORT, (long) port);
        }

        Span span = spanBuilder.startSpan();
        Scope scope = Context.current().with(span).makeCurrent();

        // Propagation W3C — inject traceparent/baggage dans les headers sortants
        TextMapPropagator propagator = W3CPropagators.textMap();
        propagator.inject(Context.current(), requestContext, HEADER_SETTER);

        requestContext.setProperty(SPAN_PROPERTY, span);
        requestContext.setProperty(SCOPE_PROPERTY, scope);
    }

    /**
     * Renvoie l'URL débarrassée du fragment (jamais transmis côté wire) et du userinfo
     * éventuel — bonnes pratiques OTel (RFC 7235 §5.1.2 + GDPR : pas de credentials
     * en clair dans les attributs de trace).
     */
    private String sanitize(URI uri) {
        if (uri.getUserInfo() == null && uri.getFragment() == null) {
            return uri.toString();
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception e) {
            return uri.toString();
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        if (scheme == null) return false;
        return ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    /** Surchargeable pour tests sans GlobalOpenTelemetry. */
    protected OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
