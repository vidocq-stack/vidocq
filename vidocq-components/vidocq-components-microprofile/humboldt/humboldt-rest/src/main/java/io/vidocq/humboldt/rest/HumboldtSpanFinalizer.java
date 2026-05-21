package io.vidocq.humboldt.rest;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * {@link ExceptionMapper} fallback qui termine le span démarré par
 * {@link HumboldtServerRequestFilter} quand une exception remonte hors de
 * la méthode de ressource — cas où le {@link HumboldtServerResponseFilter}
 * n'est pas appelé par le container JAX-RS.
 *
 * <p><b>Pourquoi nécessaire</b> : selon la spec JAX-RS §10.2.7 les
 * {@code ContainerResponseFilter} doivent être invoqués MÊME quand un
 * {@code ExceptionMapper} transforme l'exception en {@code Response}.
 * Certains containers (dont Cassini en cassini-core 0.1.0-SNAPSHOT — cf.
 * {@code Invoker.java:365}) court-circuitent ce flow et passent directement
 * de {@code ExceptionMapper.toResponse()} au marshalling sans appeler les
 * response filters. Sans ce mapper fallback, le span SERVER créé côté
 * request filter ne serait jamais {@code end()}'d et resterait invisible
 * dans l'exporter.</p>
 *
 * <p><b>Sélection JAX-RS</b> : {@code ExceptionMapper<Throwable>} est le plus
 * générique possible — il n'est sélectionné par le container que si AUCUN
 * mapper applicatif plus spécifique ne matche l'exception. Les apps peuvent
 * donc fournir leurs propres {@code ExceptionMapper<UserSpecificException>}
 * sans collision. Pour ce dernier cas (mapper user qui match), le span
 * sera (devrait être) terminé par le response filter normal — sauf si le
 * container a aussi le bug Cassini, auquel cas l'app devra explicitement
 * terminer le span elle-même.</p>
 *
 * <p>Quand le bug Cassini sera corrigé (response filters appelés après
 * ExceptionMapper), ce code devient redondant mais pas nocif — le span
 * a déjà été end()'d par le response filter, l'instanceof Span retournera
 * false (la propriété aura été removeProperty'd), et le mapper renvoie
 * juste une 500 générique.</p>
 */
@Provider
@ApplicationScoped
@Priority(jakarta.ws.rs.Priorities.USER + 1000)
public class HumboldtSpanFinalizer implements ExceptionMapper<Throwable> {

    private static final Logger LOG = System.getLogger(HumboldtSpanFinalizer.class.getName());
    private static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
            AttributeKey.longKey("http.response.status_code");

    @Context
    ContainerRequestContext requestContext;

    @Override
    public Response toResponse(Throwable exception) {
        finalizeSpan(exception);
        return Response.status(500)
                .entity(exception.getClass().getSimpleName()
                        + ": " + (exception.getMessage() != null ? exception.getMessage() : ""))
                .type("text/plain")
                .build();
    }

    private void finalizeSpan(Throwable t) {
        if (requestContext == null) return;
        Object spanObj = requestContext.getProperty(HumboldtServerRequestFilter.SPAN_PROPERTY);
        Object scopeObj = requestContext.getProperty(HumboldtServerRequestFilter.SCOPE_PROPERTY);
        if (!(spanObj instanceof Span span)) return;

        span.recordException(t);
        span.setStatus(StatusCode.ERROR,
                t.getClass().getSimpleName()
                        + ": " + (t.getMessage() != null ? t.getMessage() : ""));
        span.setAttribute(HTTP_RESPONSE_STATUS_CODE, 500L);
        try {
            if (scopeObj instanceof Scope scope) {
                scope.close();
            }
        } finally {
            span.end();
        }
        requestContext.removeProperty(HumboldtServerRequestFilter.SPAN_PROPERTY);
        requestContext.removeProperty(HumboldtServerRequestFilter.SCOPE_PROPERTY);
        LOG.log(Level.DEBUG, "Humboldt span SERVER terminé via ExceptionMapper fallback : {0}",
                t.getClass().getSimpleName());
    }
}
