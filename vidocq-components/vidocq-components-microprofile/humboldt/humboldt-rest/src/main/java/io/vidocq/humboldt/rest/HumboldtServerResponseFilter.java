package io.vidocq.humboldt.rest;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * {@link ContainerResponseFilter} symétrique à {@link HumboldtServerRequestFilter} —
 * récupère le span démarré côté request, ajoute {@code http.response.status_code},
 * met le statut ERROR si le code est ≥ 500, ferme {@link Scope} et span.
 */
@Provider
@ApplicationScoped
public class HumboldtServerResponseFilter implements ContainerResponseFilter {

    static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE = AttributeKey.longKey("http.response.status_code");

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object spanObj = requestContext.getProperty(HumboldtServerRequestFilter.SPAN_PROPERTY);
        Object scopeObj = requestContext.getProperty(HumboldtServerRequestFilter.SCOPE_PROPERTY);
        if (!(spanObj instanceof Span span)) return;

        int status = responseContext.getStatus();
        span.setAttribute(HTTP_RESPONSE_STATUS_CODE, (long) status);
        if (status >= 500) {
            span.setStatus(StatusCode.ERROR, "HTTP " + status);
        }
        try {
            if (scopeObj instanceof Scope scope) {
                scope.close();
            }
        } finally {
            span.end();
        }
        requestContext.removeProperty(HumboldtServerRequestFilter.SPAN_PROPERTY);
        requestContext.removeProperty(HumboldtServerRequestFilter.SCOPE_PROPERTY);
    }
}
