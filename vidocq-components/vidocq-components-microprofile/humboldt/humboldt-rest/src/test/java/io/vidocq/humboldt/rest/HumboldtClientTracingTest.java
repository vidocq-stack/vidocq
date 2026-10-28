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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.vidocq.humboldt.rest.HumboldtClientRequestFilter.HTTP_REQUEST_METHOD;
import static io.vidocq.humboldt.rest.HumboldtClientRequestFilter.SERVER_ADDRESS;
import static io.vidocq.humboldt.rest.HumboldtClientRequestFilter.SERVER_PORT;
import static io.vidocq.humboldt.rest.HumboldtClientRequestFilter.URL_FULL;
import static io.vidocq.humboldt.rest.HumboldtClientResponseFilter.HTTP_RESPONSE_STATUS_CODE;
import static io.vidocq.humboldt.rest.JaxRsFakes.clientRequest;
import static io.vidocq.humboldt.rest.JaxRsFakes.clientResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLIENT spans of {@link HumboldtClientRequestFilter} / {@link HumboldtClientResponseFilter}, and their
 * registration by {@link HumboldtClientTracingFeature} and {@link HumboldtMpRestClientListener}.
 */
class HumboldtClientTracingTest {

    private static final String METHOD_GET = "GET";
    private static final String REGISTER = "register";
    private static final String METHOD_POST = "POST";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private OpenTelemetry otel;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        SdkTracerProvider tracerProvider = provider;
        otel = new OpenTelemetry() {
            @Override public TracerProvider getTracerProvider() { return tracerProvider; }
            @Override public ContextPropagators getPropagators() { return W3CPropagators.get(); }
        };
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        provider.close();
    }

    @Test
    void client_span_carries_method_url_address_port_and_propagates_traceparent() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        Map<String, Object> props = new HashMap<>();
        ClientRequestContext req = clientRequest(METHOD_POST, URI.create("http://api.example.com:8081/orders?x=1"),
                headers, props);

        filter().filter(req);
        String traceparent = (String) headers.getFirst("traceparent");
        new HumboldtClientResponseFilter().filter(req, clientResponse(201));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals(METHOD_POST, span.name());
        assertEquals(SpanKind.CLIENT, span.kind());
        assertEquals(METHOD_POST, span.attributes().get(HTTP_REQUEST_METHOD));
        assertEquals("http://api.example.com:8081/orders?x=1", span.attributes().get(URL_FULL));
        assertEquals("api.example.com", span.attributes().get(SERVER_ADDRESS));
        assertEquals(8081L, span.attributes().get(SERVER_PORT));
        assertEquals(201L, span.attributes().get(HTTP_RESPONSE_STATUS_CODE));
        assertEquals(StatusCode.UNSET, span.status().code());
        assertEquals("00-" + span.spanContext().getTraceId() + "-" + span.spanContext().getSpanId() + "-01",
                traceparent, "the W3C traceparent header names the CLIENT span");
        assertTrue(props.isEmpty(), "the response filter removes its properties");
        assertFalse(Span.current().getSpanContext().isValid(), "the client scope is closed");
    }

    @Test
    void url_full_drops_user_info_and_fragment() {
        String url = traced(URI.create("https://user:secret@api.example.com/p?q=1#frag"));
        assertEquals("https://api.example.com/p?q=1", url);
        assertEquals("http://api.example.com/p", traced(URI.create("http://api.example.com/p#top")));
    }

    @Test
    void url_full_keeps_an_opaque_uri_it_cannot_rebuild() {
        assertEquals("mailto:someone@example.com#frag", traced(URI.create("mailto:someone@example.com#frag")));
    }

    @Test
    void default_ports_are_omitted_and_a_port_without_scheme_is_kept() {
        traced(URI.create("http://api.example.com:80/a"));
        traced(URI.create("https://api.example.com:443/b"));
        traced(URI.create("//api.example.com:80/c"));
        traced(URI.create("/relative"));

        List<SpanData> spans = exporter.getFinishedSpans();
        assertNull(spans.get(0).attributes().get(SERVER_PORT));
        assertNull(spans.get(1).attributes().get(SERVER_PORT));
        assertEquals(80L, spans.get(2).attributes().get(SERVER_PORT));
        assertNull(spans.get(3).attributes().get(SERVER_ADDRESS));
    }

    @Test
    void client_error_status_marks_the_span_as_error() {
        ClientRequestContext req = clientRequest(METHOD_GET, URI.create("http://api.example.com/missing"),
                new MultivaluedHashMap<>(), new HashMap<>());
        filter().filter(req);
        new HumboldtClientResponseFilter().filter(req, clientResponse(404));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals(StatusCode.ERROR, span.status().code());
        assertEquals("HTTP 404", span.status().description());
    }

    @Test
    void response_filter_ignores_a_request_without_span() {
        Map<String, Object> props = new HashMap<>();
        props.put(HumboldtClientRequestFilter.SCOPE_PROPERTY, "not a scope");
        new HumboldtClientResponseFilter().filter(
                clientRequest(METHOD_GET, URI.create("http://x/"), new MultivaluedHashMap<>(), props),
                clientResponse(200));
        assertTrue(exporter.getFinishedSpans().isEmpty());
        assertEquals(1, props.size());
    }

    @Test
    void response_filter_ends_a_span_without_scope() {
        Map<String, Object> props = new HashMap<>();
        props.put(HumboldtClientRequestFilter.SPAN_PROPERTY, provider.get("test").spanBuilder("bare").startSpan());
        new HumboldtClientResponseFilter().filter(
                clientRequest(METHOD_GET, URI.create("http://x/"), new MultivaluedHashMap<>(), props),
                clientResponse(500));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals("bare", span.name());
        assertEquals(500L, span.attributes().get(HTTP_RESPONSE_STATUS_CODE));
        assertTrue(props.isEmpty());
    }

    @Test
    void default_filter_uses_global_open_telemetry() {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(otel);
        ClientRequestContext req = clientRequest(METHOD_GET, URI.create("http://api.example.com/g"),
                new MultivaluedHashMap<>(), new HashMap<>());
        new HumboldtClientRequestFilter().filter(req);
        new HumboldtClientResponseFilter().filter(req, clientResponse(200));

        assertEquals("io.vidocq.humboldt.rest.client",
                exporter.getFinishedSpans().getFirst().instrumentationScope().name());
    }

    @Test
    void feature_registers_both_filters_once() {
        List<Class<?>> registered = new ArrayList<>();
        FeatureContext context = featureContext(registered);

        HumboldtClientTracingFeature feature = new HumboldtClientTracingFeature();
        assertTrue(feature.configure(context));
        assertTrue(feature.configure(context));

        assertEquals(List.of(HumboldtClientRequestFilter.class, HumboldtClientResponseFilter.class), registered);
    }

    @Test
    void mp_rest_client_listener_registers_both_filters() {
        List<Class<?>> registered = new ArrayList<>();
        RestClientBuilder builder = JaxRsFakes.proxy(RestClientBuilder.class, (m, args) -> {
            if (REGISTER.equals(m.getName()) && args.length == 1 && args[0] instanceof Class<?> c) {
                registered.add(c);
            }
            return null;
        });

        new HumboldtMpRestClientListener().onNewClient(Runnable.class, builder);

        assertEquals(List.of(HumboldtClientRequestFilter.class, HumboldtClientResponseFilter.class), registered);
    }

    // ----- helpers -----

    private HumboldtClientRequestFilter filter() {
        OpenTelemetry configured = otel;
        return new HumboldtClientRequestFilter() {
            @Override protected OpenTelemetry openTelemetry() { return configured; }
        };
    }

    /** Traces a GET to {@code uri} and returns the {@code url.full} of its span. */
    private String traced(URI uri) {
        ClientRequestContext req = clientRequest(METHOD_GET, uri, new MultivaluedHashMap<>(), new HashMap<>());
        filter().filter(req);
        new HumboldtClientResponseFilter().filter(req, clientResponse(200));
        return exporter.getFinishedSpans().getLast().attributes().get(URL_FULL);
    }

    private static FeatureContext featureContext(List<Class<?>> registered) {
        Configuration configuration = JaxRsFakes.proxy(Configuration.class, (m, args) ->
                "isRegistered".equals(m.getName()) ? registered.contains(args[0]) : JaxRsFakes.defaultFor(m));
        FeatureContext[] self = new FeatureContext[1];
        self[0] = JaxRsFakes.proxy(FeatureContext.class, (m, args) -> switch (m.getName()) {
            case "getConfiguration" -> configuration;
            case REGISTER -> {
                registered.add((Class<?>) args[0]);
                yield self[0];
            }
            default -> JaxRsFakes.defaultFor(m);
        });
        return self[0];
    }
}
