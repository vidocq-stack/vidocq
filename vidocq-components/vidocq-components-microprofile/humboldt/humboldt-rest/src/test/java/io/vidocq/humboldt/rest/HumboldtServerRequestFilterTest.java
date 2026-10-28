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
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.HTTP_ROUTE;
import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.HTTP_ROUTE_PROPERTY;
import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.SERVER_ADDRESS;
import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.SERVER_PORT;
import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.URL_PATH;
import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.URL_QUERY;
import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.URL_SCHEME;
import static io.vidocq.humboldt.rest.HumboldtServerRequestFilter.URL_SCHEME_PROPERTY;
import static io.vidocq.humboldt.rest.JaxRsFakes.containerRequest;
import static io.vidocq.humboldt.rest.JaxRsFakes.containerResponse;
import static io.vidocq.humboldt.rest.JaxRsFakes.uriInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantic-convention attributes, {@code http.route} reconstruction and propagator selection of
 * {@link HumboldtServerRequestFilter}, with the span ended by {@link HumboldtServerResponseFilter}.
 */
class HumboldtServerRequestFilterTest {

    private static final String CONTEXT_BASE = "http://example.com:8080/ctx/";
    private static final String METHOD_GET = "GET";
    private static final String USERS_7 = "users/7";
    private static final String USERS_ROUTE = "/ctx/users/{id}";
    private static final String HTTP = "http";
    private static final String LOCALHOST = "http://localhost/";
    private static final String X_CUSTOM = "x-custom";
    private static final String X_EMPTY = "x-empty";
    private static final String MANUAL = "manual";
    private static final String NO_PATH = "";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private final List<Attributes> histogramRecords = new ArrayList<>();

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        installGlobal(W3CPropagators.get(), JaxRsFakes.recordingMeterProvider(histogramRecords));
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        provider.close();
    }

    @Test
    void sets_route_query_address_and_port_from_the_matched_resource() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        filter.resourceInfo = resourceInfo(UsersResource.class, "byId");
        ContainerRequestContext req = containerRequest(METHOD_GET,
                uriInfo(URI.create(CONTEXT_BASE), USERS_7, URI.create(CONTEXT_BASE + "users/7?a=1&b=2")));

        filter.filter(req);
        assertEquals(USERS_ROUTE, req.getProperty(HTTP_ROUTE_PROPERTY));
        assertEquals(HTTP, req.getProperty(URL_SCHEME_PROPERTY));
        new HumboldtServerResponseFilter().filter(req, containerResponse(200));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals("GET /ctx/users/{id}", span.name());
        assertEquals(USERS_ROUTE, span.attributes().get(HTTP_ROUTE));
        assertEquals("/ctx/users/7", span.attributes().get(URL_PATH));
        assertEquals("a=1&b=2", span.attributes().get(URL_QUERY));
        assertEquals(HTTP, span.attributes().get(URL_SCHEME));
        assertEquals("example.com", span.attributes().get(SERVER_ADDRESS));
        assertEquals(8080L, span.attributes().get(SERVER_PORT));
        assertEquals("io.vidocq.humboldt.rest", span.instrumentationScope().name(),
                "the default tracer comes from GlobalOpenTelemetry");
    }

    @Test
    void records_the_request_duration_with_route_scheme_and_error_type() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        filter.resourceInfo = resourceInfo(UsersResource.class, "byId");
        ContainerRequestContext req = containerRequest("DELETE",
                uriInfo(URI.create(CONTEXT_BASE), USERS_7, URI.create(CONTEXT_BASE + USERS_7)));

        filter.filter(req);
        new HumboldtServerResponseFilter().filter(req, containerResponse(404));

        assertEquals(1, histogramRecords.size());
        Attributes recorded = histogramRecords.getFirst();
        assertEquals("DELETE", recorded.get(HumboldtServerResponseFilter.HTTP_REQUEST_METHOD));
        assertEquals(404L, recorded.get(HumboldtServerResponseFilter.HTTP_RESPONSE_STATUS_CODE));
        assertEquals("404", recorded.get(io.opentelemetry.api.common.AttributeKey.stringKey("error.type")));
        assertEquals(USERS_ROUTE, recorded.get(HumboldtServerResponseFilter.HTTP_ROUTE));
        assertEquals(HTTP, recorded.get(HumboldtServerResponseFilter.URL_SCHEME));
    }

    @Test
    void omits_the_default_port_of_the_scheme() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        filterAndEnd(filter, containerRequest(METHOD_GET,
                uriInfo(URI.create("https://secure.example.com:443/"), "a", URI.create("https://secure.example.com/a"))));
        filterAndEnd(filter, containerRequest(METHOD_GET,
                uriInfo(URI.create("http://plain.example.com:80/"), "b", URI.create("http://plain.example.com/b"))));

        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals(2, spans.size());
        for (SpanData span : spans) {
            assertNull(span.attributes().get(SERVER_PORT), span.name());
        }
        assertEquals("https", spans.getFirst().attributes().get(URL_SCHEME));
    }

    @Test
    void joins_class_and_method_paths_without_doubling_slashes() {
        assertEquals("GET /ctx/span", routeOf(RootResource.class, "span", CONTEXT_BASE));
        assertEquals("GET /items/all", routeOf(ItemsResource.class, "all", LOCALHOST));
        assertEquals("GET /items", routeOf(ItemsResource.class, "list", LOCALHOST));
        assertEquals("GET /ctx/plain", routeOf(NoPathResource.class, "plain", CONTEXT_BASE));
    }

    @Test
    void uses_the_method_path_when_the_resource_class_is_unknown() throws NoSuchMethodException {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        Method method = ItemsResource.class.getMethod("all");
        filter.resourceInfo = new FixedResourceInfo(null, method);
        filterAndEnd(filter, containerRequest(METHOD_GET,
                uriInfo(URI.create(LOCALHOST), "items/all", URI.create("http://localhost/items/all"))));

        assertEquals("GET /all", exporter.getFinishedSpans().getFirst().name());
    }

    @Test
    void falls_back_to_the_path_when_no_resource_was_matched() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        filter.resourceInfo = new FixedResourceInfo(null, null);
        filterAndEnd(filter, containerRequest(METHOD_GET,
                uriInfo(URI.create(CONTEXT_BASE), "/missing", URI.create(CONTEXT_BASE + "missing"))));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals("GET /ctx/missing", span.name());
        assertEquals("/ctx/missing", span.attributes().get(URL_PATH));
    }

    @Test
    void empty_relative_path_maps_to_the_base_path() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        filterAndEnd(filter, containerRequest(METHOD_GET,
                uriInfo(URI.create(CONTEXT_BASE), NO_PATH, URI.create(CONTEXT_BASE))));
        filterAndEnd(filter, containerRequest(METHOD_GET,
                uriInfo(URI.create("http://localhost"), null, URI.create("http://localhost"))));

        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals("/ctx", spans.get(0).attributes().get(URL_PATH));
        assertEquals("/", spans.get(1).attributes().get(URL_PATH));
    }

    @Test
    void uses_the_request_uri_when_the_base_uri_is_absent() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        filterAndEnd(filter, containerRequest("PUT",
                uriInfo(null, "orders", URI.create("http://orders.example.com:9090/orders"))));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals("/orders", span.attributes().get(URL_PATH));
        assertEquals(HTTP, span.attributes().get(URL_SCHEME));
        assertEquals("orders.example.com", span.attributes().get(SERVER_ADDRESS));
        assertEquals(9090L, span.attributes().get(SERVER_PORT));
    }

    @Test
    void relative_request_uri_without_base_sets_no_scheme_address_or_port() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        ContainerRequestContext req = containerRequest(METHOD_GET, uriInfo(null, "x", URI.create("/x")));
        filter.filter(req);

        assertNull(req.getProperty(URL_SCHEME_PROPERTY));
        new HumboldtServerResponseFilter().filter(req, containerResponse(204));
        SpanData span = exporter.getFinishedSpans().getFirst();
        assertNull(span.attributes().get(URL_SCHEME));
        assertNull(span.attributes().get(SERVER_ADDRESS));
        assertNull(span.attributes().get(SERVER_PORT));
    }

    @Test
    void extracts_through_the_global_propagator_when_one_is_configured() {
        RecordingPropagator propagator = new RecordingPropagator();
        GlobalOpenTelemetry.resetForTest();
        installGlobal(ContextPropagators.create(propagator), MeterProvider.noop());
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(X_CUSTOM, "v1");
        headers.put(X_EMPTY, new ArrayList<>());

        filterAndEnd(new HumboldtServerRequestFilter(), containerRequest(METHOD_GET,
                uriInfo(URI.create(LOCALHOST), "p", URI.create("http://localhost/p")),
                headers, new HashMap<>()));

        assertEquals(Set.of(X_CUSTOM, X_EMPTY), propagator.keys);
        assertEquals("v1", propagator.custom);
        assertNull(propagator.empty, "a header without values reads as absent");
        assertNull(propagator.missing);
        assertNull(propagator.fromNullCarrier, "a null carrier reads as absent");
    }

    @Test
    void default_propagator_hooks_return_the_w3c_propagators() {
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        assertSame(W3CPropagators.get(), filter.propagatorsAdapter());
        assertSame(GlobalOpenTelemetry.get(), filter.openTelemetry());
    }

    @Test
    void response_filter_ends_a_span_without_scope_start_time_or_route() {
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext req = containerRequest(METHOD_GET,
                uriInfo(URI.create(LOCALHOST), NO_PATH, URI.create(LOCALHOST)),
                new MultivaluedHashMap<>(), props);
        req.setProperty(HumboldtServerRequestFilter.SPAN_PROPERTY,
                provider.get("test").spanBuilder(MANUAL).startSpan());

        new HumboldtServerResponseFilter().filter(req, containerResponse(503));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals(MANUAL, span.name());
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status().code());
        assertTrue(histogramRecords.isEmpty(), "no start time: no duration is recorded");
        assertTrue(props.isEmpty());
    }

    @Test
    void response_filter_records_only_known_route_and_scheme() {
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext req = containerRequest(METHOD_GET,
                uriInfo(URI.create(LOCALHOST), NO_PATH, URI.create(LOCALHOST)),
                new MultivaluedHashMap<>(), props);
        req.setProperty(HumboldtServerRequestFilter.SPAN_PROPERTY,
                provider.get("test").spanBuilder(MANUAL).startSpan());
        req.setProperty(HumboldtServerRequestFilter.START_NANOS_PROPERTY, System.nanoTime());

        new HumboldtServerResponseFilter().filter(req, containerResponse(200));

        Attributes recorded = histogramRecords.getFirst();
        assertNull(recorded.get(HumboldtServerResponseFilter.HTTP_ROUTE));
        assertNull(recorded.get(HumboldtServerResponseFilter.URL_SCHEME));
        assertNull(recorded.get(io.opentelemetry.api.common.AttributeKey.stringKey("error.type")));
        assertEquals(1, exporter.getFinishedSpans().size());
    }

    @Test
    void response_filter_still_ends_the_span_when_the_meter_fails() {
        GlobalOpenTelemetry.resetForTest();
        installGlobal(W3CPropagators.get(), JaxRsFakes.failingMeterProvider());
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        ContainerRequestContext req = containerRequest(METHOD_GET,
                uriInfo(URI.create(LOCALHOST), "f", URI.create("http://localhost/f")));

        filter.filter(req);
        new HumboldtServerResponseFilter().filter(req, containerResponse(200));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals(200L, span.attributes().get(HumboldtServerResponseFilter.HTTP_RESPONSE_STATUS_CODE));
        assertFalse(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid(),
                "the request scope is closed");
    }

    // ----- helpers -----

    private String routeOf(Class<?> resource, String method, String base) {
        exporter.reset();
        HumboldtServerRequestFilter filter = new HumboldtServerRequestFilter();
        filter.resourceInfo = resourceInfo(resource, method);
        filterAndEnd(filter, containerRequest(METHOD_GET,
                uriInfo(URI.create(base), "ignored", URI.create(base + "ignored"))));
        return exporter.getFinishedSpans().getFirst().name();
    }

    /** Runs the request filter, then ends the span through the response filter. */
    private static void filterAndEnd(HumboldtServerRequestFilter filter, ContainerRequestContext req) {
        filter.filter(req);
        new HumboldtServerResponseFilter().filter(req, containerResponse(200));
    }

    private void installGlobal(ContextPropagators propagators, MeterProvider meterProvider) {
        SdkTracerProvider tracerProvider = provider;
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(new OpenTelemetry() {
            @Override public TracerProvider getTracerProvider() { return tracerProvider; }
            @Override public ContextPropagators getPropagators() { return propagators; }
            @Override public MeterProvider getMeterProvider() { return meterProvider; }
        });
    }

    private static ResourceInfo resourceInfo(Class<?> resource, String methodName) {
        try {
            return new FixedResourceInfo(resource, resource.getMethod(methodName));
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(methodName, e);
        }
    }

    private record FixedResourceInfo(Class<?> resourceClass, Method resourceMethod) implements ResourceInfo {
        @Override public Method getResourceMethod() { return resourceMethod; }
        @Override public Class<?> getResourceClass() { return resourceClass; }
    }

    /** Records what the filter's header getter exposes. */
    private static final class RecordingPropagator implements TextMapPropagator {
        Set<String> keys;
        String custom;
        String empty;
        String missing;
        String fromNullCarrier;

        @Override public Collection<String> fields() { return List.of(); }

        @Override public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
            // extraction only
        }

        @Override public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
            keys = new java.util.HashSet<>();
            getter.keys(carrier).forEach(keys::add);
            custom = getter.get(carrier, X_CUSTOM);
            empty = getter.get(carrier, X_EMPTY);
            missing = getter.get(carrier, "x-missing");
            fromNullCarrier = getter.get(null, X_CUSTOM);
            return context;
        }
    }

    @Path("/users")
    public static class UsersResource {
        @GET @Path("{id}") public String byId() { return "u"; }
    }

    @Path("/")
    public static class RootResource {
        @GET @Path("/span") public String span() { return "s"; }
    }

    @Path("items")
    public static class ItemsResource {
        @GET @Path("all") public String all() { return "a"; }
        @GET public String list() { return "l"; }
    }

    public static class NoPathResource {
        @GET @Path("/plain") public String plain() { return "p"; }
    }
}
