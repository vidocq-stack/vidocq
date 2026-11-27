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
package io.vidocq.humboldt.runtime;

import io.opentelemetry.api.trace.Span;
import io.vidocq.humboldt.sdk.log.bridge.HumboldtJulHandler;
import io.vidocq.humboldt.sdk.metric.export.InMemoryMetricExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OTLP, logging and sampler selection of {@link HumboldtAutoConfigure}. */
class HumboldtAutoConfigureExportersTest {

    private static final String DISABLED = "OTEL_SDK_DISABLED";
    private static final String TRACES = "OTEL_TRACES_EXPORTER";
    private static final String METRICS = "OTEL_METRICS_EXPORTER";
    private static final String LOGS = "OTEL_LOGS_EXPORTER";
    private static final String SAMPLER = "OTEL_TRACES_SAMPLER";
    private static final String NONE = "none";
    private static final String LOGGING = "logging";
    private static final String OTLP = "otlp";
    private static final String PROTOCOL = "OTEL_EXPORTER_OTLP_PROTOCOL";
    private static final String JSON = "application/json";
    private static final String PROTOBUF = "application/x-protobuf";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    /** Path → Content-Type of the last request to it. */
    private final Map<String, String> contentTypes = new java.util.concurrent.ConcurrentHashMap<>();
    private ServerSocket server;
    private String endpoint;

    /** Minimal HTTP/1.1 collector on java.base sockets: records "path x-team=header" and answers 200. */
    @BeforeEach
    void startCollector() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException _) {
                    return;
                }
            }
        });
        endpoint = "http://127.0.0.1:" + server.getLocalPort();
    }

    private void serve(Socket socket) {
        try (socket; InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = socket.getOutputStream()) {
            String requestLine;
            while ((requestLine = readLine(in)) != null && !requestLine.isEmpty()) {
                String team = null;
                String contentType = null;
                int length = 0;
                String header;
                while ((header = readLine(in)) != null && !header.isEmpty()) {
                    int colon = header.indexOf(':');
                    String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = header.substring(colon + 1).trim();
                    if (name.equals("x-team")) team = value;
                    if (name.equals("content-type")) contentType = value;
                    if (name.equals("content-length")) length = Integer.parseInt(value);
                }
                in.readNBytes(length);
                String path = requestLine.split(" ")[1];
                if (contentType != null) contentTypes.put(path, contentType);
                requests.add(path + " x-team=" + team);
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException _) {
            // client went away
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return line.toString(StandardCharsets.US_ASCII).stripTrailing();
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
    }

    @AfterEach
    void stopCollectorAndJulBridge() throws IOException {
        server.close();
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        for (java.util.logging.Handler h : root.getHandlers()) {
            if (h instanceof HumboldtJulHandler) root.removeHandler(h);
        }
    }

    private static Map<String, String> enabled(String traces, String metrics, String logs) {
        Map<String, String> env = new HashMap<>();
        env.put(DISABLED, "false");
        env.put(TRACES, traces);
        env.put(METRICS, metrics);
        env.put(LOGS, logs);
        return env;
    }

    private static boolean rootSpanSampled(String sampler, String arg) {
        Map<String, String> env = enabled(NONE, NONE, NONE);
        env.put(SAMPLER, sampler);
        if (arg != null) env.put("OTEL_TRACES_SAMPLER_ARG", arg);
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            Span span = h.getTracerProvider().get("sampler").spanBuilder("root").startSpan();
            span.end();
            return span.getSpanContext().isSampled();
        }
    }

    @Test
    void every_sampler_name_is_honoured() {
        assertTrue(rootSpanSampled("always_on", null));
        assertFalse(rootSpanSampled("always_off", null));
        assertFalse(rootSpanSampled("traceidratio", "0.0"));
        assertTrue(rootSpanSampled("traceidratio", "1.0"));
        assertFalse(rootSpanSampled("parentbased_always_off", null));
        assertFalse(rootSpanSampled("parentbased_traceidratio", "0.0"));
        assertTrue(rootSpanSampled("parentbased_always_on", null));
        assertTrue(rootSpanSampled("unknown-sampler", null));
    }

    @Test
    void otlp_exporters_post_to_the_signal_paths_with_the_configured_headers() {
        Map<String, String> env = enabled(OTLP, OTLP, OTLP);
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
        env.put("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", endpoint + "/custom/logs");
        env.put("OTEL_EXPORTER_OTLP_HEADERS", "x-team=core,malformed,=empty");
        env.put("OTEL_METRIC_EXPORT_INTERVAL", "3600000");
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            h.getTracerProvider().get("otlp").spanBuilder("exported").startSpan().end();
            h.getMeterProvider().get("otlp").counterBuilder("hits").build().add(1);
            h.getLogsBridge().get("otlp").logRecordBuilder().setBody("hello").emit();
            assertTrue(h.flush().join(10, TimeUnit.SECONDS).isSuccess());
            assertNull(h.inMemorySpanExporter());
        }
        assertTrue(requests.contains("/v1/traces x-team=core"), requests::toString);
        assertTrue(requests.contains("/v1/metrics x-team=core"), requests::toString);
        assertTrue(requests.contains("/custom/logs x-team=core"), requests::toString);
    }

    @Test
    void per_signal_trace_and_metric_endpoints_override_the_base_endpoint() {
        Map<String, String> env = enabled(OTLP, OTLP, NONE);
        env.put("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", endpoint + "/t");
        env.put("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", endpoint + "/m");
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            h.getTracerProvider().get("otlp").spanBuilder("exported").startSpan().end();
            h.getMeterProvider().get("otlp").counterBuilder("hits").build().add(1);
            assertTrue(h.flush().join(10, TimeUnit.SECONDS).isSuccess());
        }
        assertTrue(requests.contains("/t x-team=null"), requests::toString);
        assertTrue(requests.contains("/m x-team=null"), requests::toString);
    }

    /** Exports one span, one metric and one log record through OTLP with {@code env}, to the local collector. */
    private void exportOneOfEach(Map<String, String> env) {
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
        env.put("OTEL_METRIC_EXPORT_INTERVAL", "3600000");
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            h.getTracerProvider().get("otlp").spanBuilder("exported").startSpan().end();
            h.getMeterProvider().get("otlp").counterBuilder("hits").build().add(1);
            h.getLogsBridge().get("otlp").logRecordBuilder().setBody("hello").emit();
            assertTrue(h.flush().join(10, TimeUnit.SECONDS).isSuccess());
        }
    }

    @Test
    void otlp_exporters_speak_http_protobuf_when_no_protocol_is_set() {
        exportOneOfEach(enabled(OTLP, OTLP, OTLP));

        assertEquals(Map.of("/v1/traces", PROTOBUF, "/v1/metrics", PROTOBUF, "/v1/logs", PROTOBUF), contentTypes);
    }

    @Test
    void otel_exporter_otlp_protocol_http_json_switches_every_signal_to_json() {
        Map<String, String> env = enabled(OTLP, OTLP, OTLP);
        env.put(PROTOCOL, "http/json");

        exportOneOfEach(env);

        assertEquals(Map.of("/v1/traces", JSON, "/v1/metrics", JSON, "/v1/logs", JSON), contentTypes);
    }

    @Test
    void the_protocol_is_read_from_the_system_property_form_too() {
        Map<String, String> env = enabled(OTLP, NONE, NONE);
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(
                EnvConfig.of(env, Map.of("otel.exporter.otlp.protocol", "http/protobuf")))) {
            h.getTracerProvider().get("otlp").spanBuilder("exported").startSpan().end();
            assertTrue(h.flush().join(10, TimeUnit.SECONDS).isSuccess());
        }

        assertEquals(PROTOBUF, contentTypes.get("/v1/traces"));
    }

    @Test
    void a_per_signal_protocol_overrides_the_general_one() {
        Map<String, String> env = enabled(OTLP, OTLP, OTLP);
        env.put(PROTOCOL, "http/protobuf");
        env.put("OTEL_EXPORTER_OTLP_METRICS_PROTOCOL", "http/json");
        env.put("OTEL_EXPORTER_OTLP_LOGS_PROTOCOL", "http/json");

        exportOneOfEach(env);

        assertEquals(Map.of("/v1/traces", PROTOBUF, "/v1/metrics", JSON, "/v1/logs", JSON), contentTypes);
    }

    @Test
    void grpc_fails_the_configuration_with_a_clear_message() {
        Map<String, String> env = enabled(OTLP, NONE, NONE);
        env.put(PROTOCOL, "grpc");

        EnvConfig config = EnvConfig.of(env, Map.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HumboldtAutoConfigure.configure(config));

        assertTrue(e.getMessage().contains("OTEL_EXPORTER_OTLP_PROTOCOL")
                && e.getMessage().contains("gRPC is not supported yet"), e.getMessage());
    }

    @Test
    void a_per_signal_grpc_names_its_key() {
        Map<String, String> env = enabled(NONE, NONE, OTLP);
        env.put("OTEL_EXPORTER_OTLP_LOGS_PROTOCOL", "grpc");

        EnvConfig config = EnvConfig.of(env, Map.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HumboldtAutoConfigure.configure(config));

        assertTrue(e.getMessage().contains("OTEL_EXPORTER_OTLP_LOGS_PROTOCOL"), e.getMessage());
    }

    @Test
    void the_protocol_of_a_signal_without_an_otlp_exporter_is_not_read() {
        Map<String, String> env = enabled(NONE, LOGGING, NONE);
        env.put(PROTOCOL, "grpc");

        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            assertTrue(h.sdkTracerProvider().getSpanProcessors().isEmpty());
        }
    }

    @Test
    void logging_exporters_install_the_jul_bridge_once() {
        Map<String, String> env = enabled(LOGGING, LOGGING, LOGGING);
        try (AutoConfiguredHumboldt first = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()));
             AutoConfiguredHumboldt second = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            long bridges = java.util.Arrays.stream(java.util.logging.Logger.getLogger("").getHandlers())
                    .filter(HumboldtJulHandler.class::isInstance)
                    .count();
            assertEquals(1, bridges);
            assertNull(first.inMemoryLogRecordExporter());
            assertNull(second.inMemoryMetricExporter());
        }
    }

    @Test
    void extra_metric_exporters_and_a_sampler_override_are_used() {
        InMemoryMetricExporter extra = InMemoryMetricExporter.create();
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(
                EnvConfig.of(enabled(NONE, NONE, NONE), Map.of()),
                List.of(), Sampler.alwaysOff(), null, List.of(extra))) {
            assertEquals(Sampler.alwaysOff().description(), h.sdkTracerProvider().getSampler().description());
            h.getMeterProvider().get("extra").counterBuilder("extra.hits").build().add(2);
            assertTrue(h.flush().join(10, TimeUnit.SECONDS).isSuccess());
            assertTrue(extra.getCollected().stream().anyMatch(m -> m.name().equals("extra.hits")),
                    () -> extra.getCollected().toString());
        }
    }

    @Test
    void null_extra_metric_exporters_are_treated_as_none() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(
                EnvConfig.of(enabled(NONE, NONE, NONE), Map.of()), List.of(), null, null, null)) {
            assertTrue(h.sdkTracerProvider().getSpanProcessors().isEmpty());
            assertTrue(h.sdkLoggerProvider() != null && h.sdkMeterProvider() != null);
        }
    }
}
