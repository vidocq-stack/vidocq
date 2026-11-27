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
package io.vidocq.humboldt.exporter.otlp.http;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.vidocq.humboldt.exporter.otlp.http.OtlpProtoSchema.LOGS_REQUEST;
import static io.vidocq.humboldt.exporter.otlp.http.OtlpProtoSchema.METRICS_REQUEST;
import static io.vidocq.humboldt.exporter.otlp.http.OtlpProtoSchema.TRACES_REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The OTLP/HTTP protocols ({@code http/json}, {@code http/protobuf}) of the three exporters, on the wire. */
class OtlpHttpProtocolTest {

    private static final SpanContext CTX = SpanContext.create(
            "0123456789abcdef0123456789abcdef", "0123456789abcdef",
            TraceFlags.getSampled(), TraceState.getDefault());
    private static final Resource SERVICE = Resource.create(Attributes.of(
            io.opentelemetry.api.common.AttributeKey.stringKey("service.name"), "humboldt-wire"));
    private static final InstrumentationScope SCOPE = InstrumentationScope.of("io.vidocq.test");

    private HttpServer server;
    /** Path → the Content-Type and the body of the last request to it. */
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
    private final Map<String, byte[]> bodies = new ConcurrentHashMap<>();

    @BeforeEach
    void startCollector() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            contentTypes.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            bodies.put(path, exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopCollector() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void assertDelivered(CompletableResultCode result) {
        assertTrue(result.join(10, TimeUnit.SECONDS).isSuccess(), "export failed");
    }

    private static SpanData span() {
        return new SpanData(CTX, null, "GET /wire", SpanKind.SERVER, 1L, 2L, Attributes.empty(), List.of(),
                List.of(), StatusData.ok(), SERVICE, SCOPE);
    }

    private static MetricData metric() {
        return new MetricData(SERVICE, SCOPE, "hits", "", "", InstrumentType.COUNTER,
                AggregationTemporality.CUMULATIVE, true, List.of(new LongPointData(1L, 2L, Attributes.empty(), 3L)));
    }

    private static LogRecordData log() {
        return new LogRecordData(SERVICE, SCOPE, 1L, 2L, CTX, Severity.INFO, "INFO", "hello", Attributes.empty(),
                "");
    }

    @Test
    void http_protobuf_posts_application_x_protobuf_bodies_of_the_official_messages() {
        OtlpHttpSpanExporter spans = OtlpHttpSpanExporter.builder().setEndpoint(url("/v1/traces"))
                .setProtocol(OtlpProtocol.HTTP_PROTOBUF).build();
        OtlpHttpMetricExporter metrics = OtlpHttpMetricExporter.builder().setEndpoint(url("/v1/metrics"))
                .setProtocol(OtlpProtocol.HTTP_PROTOBUF).build();
        OtlpHttpLogExporter logs = OtlpHttpLogExporter.builder().setEndpoint(url("/v1/logs"))
                .setProtocol(OtlpProtocol.HTTP_PROTOBUF).build();

        assertDelivered(spans.export(List.of(span())));
        assertDelivered(metrics.export(List.of(metric())));
        assertDelivered(logs.export(List.of(log())));

        assertEquals("application/x-protobuf", contentTypes.get("/v1/traces"));
        assertEquals("application/x-protobuf", contentTypes.get("/v1/metrics"));
        assertEquals("application/x-protobuf", contentTypes.get("/v1/logs"));
        assertTrue(OtlpProtoSchema.decode(TRACES_REQUEST, bodies.get("/v1/traces")).toString()
                .contains("name=GET /wire"));
        assertTrue(OtlpProtoSchema.decode(METRICS_REQUEST, bodies.get("/v1/metrics")).toString()
                .contains("name=hits"));
        assertTrue(OtlpProtoSchema.decode(LOGS_REQUEST, bodies.get("/v1/logs")).toString()
                .contains("stringValue=hello"));
    }

    @Test
    void http_protobuf_is_the_default_and_posts_application_x_protobuf() {
        assertDelivered(OtlpHttpSpanExporter.builder().setEndpoint(url("/v1/traces")).build()
                .export(List.of(span())));
        assertDelivered(OtlpHttpMetricExporter.builder().setEndpoint(url("/v1/metrics")).build()
                .export(List.of(metric())));
        assertDelivered(OtlpHttpLogExporter.builder().setEndpoint(url("/v1/logs")).build()
                .export(List.of(log())));

        assertEquals("application/x-protobuf", contentTypes.get("/v1/traces"));
        assertEquals("application/x-protobuf", contentTypes.get("/v1/metrics"));
        assertEquals("application/x-protobuf", contentTypes.get("/v1/logs"));
        assertTrue(OtlpProtoSchema.decode(TRACES_REQUEST, bodies.get("/v1/traces")).toString()
                .contains("name=GET /wire"));
    }

    @Test
    void http_json_posts_application_json() {
        assertDelivered(OtlpHttpSpanExporter.builder().setEndpoint(url("/v1/traces"))
                .setProtocol(OtlpProtocol.HTTP_JSON).build().export(List.of(span())));
        assertDelivered(OtlpHttpMetricExporter.builder().setEndpoint(url("/v1/metrics"))
                .setProtocol(OtlpProtocol.HTTP_JSON).build().export(List.of(metric())));

        assertEquals("application/json", contentTypes.get("/v1/traces"));
        assertEquals("application/json", contentTypes.get("/v1/metrics"));
        assertTrue(new String(bodies.get("/v1/traces"), java.nio.charset.StandardCharsets.UTF_8)
                .startsWith("{\"resourceSpans\":["));
    }

    @Test
    void a_null_protocol_keeps_the_previous_one() {
        assertDelivered(OtlpHttpLogExporter.builder().setEndpoint(url("/v1/logs"))
                .setProtocol(OtlpProtocol.HTTP_JSON).setProtocol(null).build().export(List.of(log())));

        assertEquals("application/json", contentTypes.get("/v1/logs"));
    }

    @Test
    void the_configuration_values_name_the_two_http_protocols() {
        assertEquals(OtlpProtocol.HTTP_JSON, OtlpProtocol.fromConfigValue("http/json"));
        assertEquals(OtlpProtocol.HTTP_PROTOBUF, OtlpProtocol.fromConfigValue(" HTTP/Protobuf "));
        assertEquals("http/protobuf", OtlpProtocol.HTTP_PROTOBUF.configValue());
        assertEquals("application/json", OtlpProtocol.HTTP_JSON.contentType());
    }

    @Test
    void grpc_is_refused_with_what_to_set_instead() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OtlpProtocol.fromConfigValue("grpc"));

        assertTrue(e.getMessage().contains("gRPC is not supported yet"), e.getMessage());
        assertTrue(e.getMessage().contains("http/protobuf") && e.getMessage().contains("4318"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http", "protobuf", "http/xml"})
    void an_unknown_protocol_is_refused_with_the_supported_ones(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OtlpProtocol.fromConfigValue(value));

        assertTrue(e.getMessage().contains("http/json") && e.getMessage().contains("http/protobuf"),
                e.getMessage());
    }
}
