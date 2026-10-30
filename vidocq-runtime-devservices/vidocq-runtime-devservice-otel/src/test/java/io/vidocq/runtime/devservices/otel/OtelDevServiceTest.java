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
package io.vidocq.runtime.devservices.otel;

import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.devservices.spi.DevServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The OpenTelemetry dev service: a Grafana LGTM container (an OpenTelemetry collector in front of Grafana, Tempo,
 * Loki and Prometheus) for an application with MicroProfile Telemetry, whose OTLP endpoint it publishes. Asked by
 * the vidocq-tutorial session.
 */
class OtelDevServiceTest {

    private static final String HUMBOLDT = "io.vidocq.humboldt.runtime.HumboldtAutoConfigure";

    @Test
    void appliesToAnApplicationWithTelemetryAndNoEndpoint() {
        assertTrue(new OtelDevService().appliesWhen(ctx(Map.of(), Map.of(), Set.of(HUMBOLDT))));
    }

    @Test
    void staysOffWithoutTelemetryAndSaysWhy() {
        DevServiceContext ctx = ctx(Map.of(), Map.of(), Set.of());
        OtelDevService service = new OtelDevService();

        assertFalse(service.appliesWhen(ctx));
        assertEquals("no MicroProfile Telemetry (" + HUMBOLDT + ") on the class path", service.skipReason(ctx));
    }

    @Test
    void staysOffWhenAnEndpointIsGiven() {
        OtelDevService service = new OtelDevService();
        for (String key : new String[] {"otel.exporter.otlp.endpoint", "OTEL_EXPORTER_OTLP_ENDPOINT"}) {
            DevServiceContext ctx = ctx(Map.of(key, "http://collector:4318"), Map.of(), Set.of(HUMBOLDT));
            assertFalse(service.appliesWhen(ctx), key);
            assertEquals(key + " is set", service.skipReason(ctx));
        }
        assertNull(service.skipReason(ctx(Map.of(), Map.of(), Set.of(HUMBOLDT))));
    }

    @Test
    void theContainerExposesGrafanaAndBothOtlpPortsWithTheDevLabels() {
        GenericContainer<?> container = OtelDevService.container(ctx(Map.of(), Map.of(), Set.of(HUMBOLDT)),
                OtelDevService.DEFAULT_IMAGE, false);

        assertEquals(java.util.List.of(3000, 4317, 4318), container.getExposedPorts());
        assertEquals("true", container.getLabels().get("io.vidocq.dev"));
        assertEquals("otel", container.getLabels().get("io.vidocq.dev.service"));
        assertEquals("grafana/otel-lgtm:0.37.0", container.getDockerImageName());
    }

    @Test
    void theSdkIsSwitchedOnUnlessTheApplicationDecides() {
        assertEquals(Map.of("otel.exporter.otlp.endpoint", "http://h:1", "otel.exporter.otlp.protocol",
                        "http/protobuf", "otel.sdk.disabled", "false"),
                OtelDevService.published(ctx(Map.of(), Map.of(), Set.of(HUMBOLDT)), "http://h:1"));
        assertEquals(Map.of("otel.exporter.otlp.endpoint", "http://h:1", "otel.exporter.otlp.protocol",
                        "http/protobuf"),
                OtelDevService.published(ctx(Map.of(), Map.of("otel.sdk.disabled", "true"), Set.of(HUMBOLDT)),
                        "http://h:1"));
        assertEquals(Map.of("otel.exporter.otlp.endpoint", "http://h:1", "otel.exporter.otlp.protocol",
                        "http/protobuf"),
                OtelDevService.published(ctx(Map.of("otel.sdk.disabled", "true"), Map.of(), Set.of(HUMBOLDT)),
                        "http://h:1"));
    }

    @Test
    @Timeout(300)
    void startsTheStackAndPublishesAnEndpointThatAcceptsTraces() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available — skipping");

        OtelDevService service = new OtelDevService();
        try (HttpClient client = HttpClient.newHttpClient()) {
            Map<String, String> props = service.start(ctx(Map.of(), Map.of(), Set.of(HUMBOLDT)));

            String endpoint = props.get("otel.exporter.otlp.endpoint");
            assertTrue(endpoint.startsWith("http://"), endpoint);
            HttpResponse<String> traces = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/v1/traces"))
                            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, traces.statusCode(), traces.body());

            DevServiceState state = service.describe(props);
            String grafana = state.endpoints().get("grafana");
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(grafana + "/api/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), health.body());
        } finally {
            service.stop();
        }
    }

    private static DevServiceContext ctx(Map<String, String> props, Map<String, String> files, Set<String> classes) {
        return new DevServiceContext() {
            @Override public Optional<String> property(String key) {
                String v = props.get(key);
                return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
            }
            @Override public Map<String, String> properties() { return props; }
            @Override public Path basedir() { return Path.of("."); }
            @Override public Path resolve(String relative) { return Path.of(".").resolve(relative); }
            @Override public System.Logger log() { return System.getLogger("test"); }
            @Override public Optional<String> applicationProperty(String key) {
                return Optional.ofNullable(files.get(key));
            }
            @Override public boolean onApplicationClasspath(String className) { return classes.contains(className); }
        };
    }
}
