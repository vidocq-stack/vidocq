/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * {@link ClientRequestFilter} that starts a {@link SpanKind#CLIENT} span for each
 * outgoing HTTP request, sets the OTel HTTP semantic conventions 1.27+ attributes
 * applicable on the client side, and injects the W3C {@code traceparent} into the
 * outgoing headers for distributed propagation.
 *
 * <p>Attributes set on the span:</p>
 * <ul>
 *   <li>{@code http.request.method} — GET/POST/...</li>
 *   <li>{@code url.full} — full request URL (without fragment)</li>
 *   <li>{@code server.address} — host from the request URI</li>
 *   <li>{@code server.port} — port from the request URI (omitted if standard port for the scheme)</li>
 * </ul>
 *
 * <p>Span name: {@code "<METHOD>"} (e.g., {@code "GET"}), in line with the OTel HTTP
 * client semconv which does not template the route on the client side (the full URL
 * is sufficient; the pattern is only known server-side via {@code @Path}).</p>
 *
 * <p>The span and scope are stored in the {@link ClientRequestContext} properties
 * for symmetric retrieval by {@link HumboldtClientResponseFilter} which ends them.</p>
 *
 * <p>Priority {@link Priorities#HEADER_DECORATOR} (3000) — runs after authentication
 * filters (1000) so that the span covers the auth header cost, but before USER
 * application filters (5000) that might mutate the URI.</p>
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
     * {@link TextMapSetter} that writes the W3C headers ({@code traceparent}, {@code tracestate},
     * {@code baggage}) into the mutable {@link jakarta.ws.rs.core.MultivaluedMap} of the request
     * context. Replaces any existing value (putSingle) to honour the
     * "one trace per request" semantics.
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

        // Propagation: uses the Humboldt global TextMapPropagator. Includes W3C
        // (TraceContext + Baggage) by default + custom propagators declared via SPI
        // ConfigurablePropagatorProvider (MP Telemetry §3.3 / cluster D).
        TextMapPropagator propagator = otel.getPropagators().getTextMapPropagator();
        propagator.inject(Context.current(), requestContext, HEADER_SETTER);

        requestContext.setProperty(SPAN_PROPERTY, span);
        requestContext.setProperty(SCOPE_PROPERTY, scope);
    }

    /**
     * Returns the URL stripped of the fragment (never transmitted over the wire) and
     * of any userinfo — OTel best practices (RFC 7235 §5.1.2 + GDPR: no credentials
     * in plain text in trace attributes).
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

    /** Overridable for tests without GlobalOpenTelemetry. */
    protected OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
