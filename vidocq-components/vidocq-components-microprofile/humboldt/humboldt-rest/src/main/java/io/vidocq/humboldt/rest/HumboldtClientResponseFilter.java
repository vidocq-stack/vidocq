package io.vidocq.humboldt.rest;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;

/**
 * Symétrique à {@link HumboldtClientRequestFilter} — récupère le span démarré côté
 * request, pose {@code http.response.status_code} et passe le statut OTel à
 * {@link StatusCode#ERROR} si le code HTTP est ≥ 400.
 *
 * <p><strong>Différence vs server</strong> : OTel HTTP semconv 1.27+ §4.3 dit qu'un
 * span CLIENT est en ERROR pour <em>tout</em> code 4xx ou 5xx (l'appelant a échoué à
 * obtenir une réponse correcte), alors qu'un span SERVER ne l'est qu'à partir de 5xx
 * (le serveur peut légitimement répondre 4xx sans que ce soit son erreur).</p>
 *
 * <p>Priorité {@link Priorities#HEADER_DECORATOR} — symétrique du request filter, tri
 * descendant côté response (JAX-RS §6.3) donc s'exécute APRÈS les filtres USER (5000),
 * juste avant de retourner la Response au caller.</p>
 */
@Priority(Priorities.HEADER_DECORATOR)
public class HumboldtClientResponseFilter implements ClientResponseFilter {

    static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE = AttributeKey.longKey("http.response.status_code");

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        Object spanObj = requestContext.getProperty(HumboldtClientRequestFilter.SPAN_PROPERTY);
        Object scopeObj = requestContext.getProperty(HumboldtClientRequestFilter.SCOPE_PROPERTY);
        if (!(spanObj instanceof Span span)) return;

        int status = responseContext.getStatus();
        span.setAttribute(HTTP_RESPONSE_STATUS_CODE, (long) status);
        if (status >= 400) {
            span.setStatus(StatusCode.ERROR, "HTTP " + status);
        }
        try {
            if (scopeObj instanceof Scope scope) {
                scope.close();
            }
        } finally {
            span.end();
        }
        requestContext.removeProperty(HumboldtClientRequestFilter.SPAN_PROPERTY);
        requestContext.removeProperty(HumboldtClientRequestFilter.SCOPE_PROPERTY);
    }
}
