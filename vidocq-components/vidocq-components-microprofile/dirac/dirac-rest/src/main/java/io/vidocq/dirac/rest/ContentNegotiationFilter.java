package io.vidocq.dirac.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

/**
 * Normalise l'en-tête Accept avant le routage JAX-RS.
 *
 * <p>Règle MP Metrics §2.3 : null / vide / wildcard ({@code *}{@code /*}) → {@code text/plain}
 * (format OpenMetrics par défaut). Les valeurs explicites ({@code application/json},
 * {@code text/plain}) sont transmises telles quelles.</p>
 */
@Provider
@PreMatching
public class ContentNegotiationFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        var accept = ctx.getHeaderString(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank() || MediaType.WILDCARD.equals(accept.trim())) {
            ctx.getHeaders().putSingle(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN);
        }
    }
}
