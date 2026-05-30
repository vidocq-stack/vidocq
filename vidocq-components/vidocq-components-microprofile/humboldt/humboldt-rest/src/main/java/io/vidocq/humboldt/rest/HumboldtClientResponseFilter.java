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
 * Symmetric counterpart to {@link HumboldtClientRequestFilter} — retrieves the span
 * started on the request side, sets {@code http.response.status_code} and moves the
 * OTel status to {@link StatusCode#ERROR} if the HTTP code is ≥ 400.
 *
 * <p><strong>Difference vs server</strong>: OTel HTTP semconv 1.27+ §4.3 states that a
 * CLIENT span is in ERROR for <em>any</em> 4xx or 5xx code (the caller failed to
 * obtain a correct response), whereas a SERVER span is only in ERROR from 5xx
 * (the server may legitimately respond with 4xx without it being its fault).</p>
 *
 * <p>Priority {@link Priorities#HEADER_DECORATOR} — symmetric with the request filter,
 * descending sort on the response side (JAX-RS §6.3) so it runs AFTER USER filters (5000),
 * just before returning the Response to the caller.</p>
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
