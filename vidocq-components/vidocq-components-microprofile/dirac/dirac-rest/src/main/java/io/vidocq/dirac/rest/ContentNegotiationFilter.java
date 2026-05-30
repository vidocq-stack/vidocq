package io.vidocq.dirac.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

/**
 * Normalizes the Accept header before JAX-RS routing.
 *
 * <p>MP Metrics §2.3 rule: null / empty / wildcard ({@code *}{@code /*}) → {@code text/plain}
 * (default OpenMetrics format). Explicit values ({@code application/json},
 * {@code text/plain}) are passed through as-is.</p>
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
