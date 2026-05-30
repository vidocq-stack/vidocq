package io.vidocq.humboldt.rest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * {@link ContainerResponseFilter} symmetric to {@link HumboldtServerRequestFilter} —
 * retrieves the span started on the request side, adds {@code http.response.status_code},
 * sets ERROR status if code ≥ 500, closes the {@link Scope} and ends the span. Also
 * records a Histogram {@code http.server.request.duration} (OTel SemConv 1.27+)
 * with attrs {@code http.request.method / http.response.status_code / http.route /
 * url.scheme} — conformant with MP Telemetry 2.1 §"HTTP server metrics".
 */
@Provider
public class HumboldtServerResponseFilter implements ContainerResponseFilter {

    static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE = AttributeKey.longKey("http.response.status_code");
    static final AttributeKey<String> HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    static final AttributeKey<String> URL_SCHEME = AttributeKey.stringKey("url.scheme");

    // No static cache of the Histogram — Arquillian harnesses re-deploy and
    // re-set GlobalOpenTelemetry between tests, so a static cache would freeze
    // the Histogram on the first deployment. The per-request lookup overhead is
    // negligible server-side (~1µs) relative to the Histogram cost itself.

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object spanObj = requestContext.getProperty(HumboldtServerRequestFilter.SPAN_PROPERTY);
        Object scopeObj = requestContext.getProperty(HumboldtServerRequestFilter.SCOPE_PROPERTY);
        Object startObj = requestContext.getProperty(HumboldtServerRequestFilter.START_NANOS_PROPERTY);
        if (!(spanObj instanceof Span span)) return;

        int status = responseContext.getStatus();
        span.setAttribute(HTTP_RESPONSE_STATUS_CODE, (long) status);
        if (status >= 500) {
            span.setStatus(StatusCode.ERROR, "HTTP " + status);
        }

        // OTel SemConv 1.27+ : http.server.request.duration — Histogram unit=s,
        // attrs http.request.method, http.response.status_code, http.route, url.scheme.
        if (startObj instanceof Long startNanos) {
            try {
                DoubleHistogram h = histogram();
                AttributesBuilder b = Attributes.builder()
                        .put(HTTP_REQUEST_METHOD, requestContext.getMethod())
                        .put(HTTP_RESPONSE_STATUS_CODE, (long) status);
                // OTel SemConv 1.27+ §HTTP: if status >= 400, add error.type
                // (status code string or exception name). MP Telemetry conformance.
                if (status >= 400) {
                    b.put(AttributeKey.stringKey("error.type"), String.valueOf(status));
                }
                Object route = requestContext.getProperty(HumboldtServerRequestFilter.HTTP_ROUTE_PROPERTY);
                if (route instanceof String r) b.put(HTTP_ROUTE, r);
                Object scheme = requestContext.getProperty(HumboldtServerRequestFilter.URL_SCHEME_PROPERTY);
                if (scheme instanceof String s) b.put(URL_SCHEME, s);
                double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                h.record(durationSec, b.build());
            } catch (RuntimeException ignored) {
                // If MeterProvider is not ready yet or an error occurs — continue without histogram.
            }
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
        requestContext.removeProperty(HumboldtServerRequestFilter.START_NANOS_PROPERTY);
        requestContext.removeProperty(HumboldtServerRequestFilter.HTTP_ROUTE_PROPERTY);
        requestContext.removeProperty(HumboldtServerRequestFilter.URL_SCHEME_PROPERTY);
    }

    private static DoubleHistogram histogram() {
        return GlobalOpenTelemetry.get()
                .getMeter("io.vidocq.humboldt.rest")
                .histogramBuilder("http.server.request.duration")
                .setDescription("Duration of HTTP server requests.")
                .setUnit("s")
                .build();
    }
}
