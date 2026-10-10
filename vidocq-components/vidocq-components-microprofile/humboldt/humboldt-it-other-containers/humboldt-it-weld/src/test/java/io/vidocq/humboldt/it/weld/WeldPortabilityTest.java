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
package io.vidocq.humboldt.it.weld;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import java.util.Map;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Humboldt jars, unchanged, under Weld SE on a class path (vidocq-workspace#15, humboldt#23):
 * the build compatible extension binds the interceptor to {@code @WithSpan}, and the producers
 * supply {@code OpenTelemetry}, {@code Tracer} and {@code Span}. Outside Vidocq nothing installs
 * the SDK, so the test does it the way an application would: {@code HumboldtAutoConfigure} then
 * {@code GlobalOpenTelemetry.set}. Nothing generated for Vauban is used.
 */
class WeldPortabilityTest {

    private static AutoConfiguredHumboldt humboldt;
    private static WeldContainer container;

    @BeforeAll
    static void start() {
        // MP Telemetry: the SDK stays off unless OTEL_SDK_DISABLED=false.
        humboldt = HumboldtAutoConfigure.configure(EnvConfig.of(Map.of(
                "OTEL_SDK_DISABLED", "false",
                "OTEL_SERVICE_NAME", "humboldt-it-weld",
                "OTEL_TRACES_EXPORTER", "in-memory",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none"), Map.of()));
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(humboldt);
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
        GlobalOpenTelemetry.resetForTest();
    }

    @BeforeEach
    void clearSpans() {
        humboldt.inMemorySpanExporter().reset();
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void withSpanMethodIsTraced() {
        assertEquals("done 42", container.select(TracedService.class).get().work("42"));

        SpanData span = humboldt.inMemorySpanExporter().getFinishedSpans().getFirst();
        assertEquals("TracedService.work", span.name());
        assertEquals("42", span.attributes().get(AttributeKey.stringKey("order.id")));
    }

    @Test
    void injectedSpanIsTheCurrentOne() {
        String injectedId = container.select(TracedService.class).get().currentSpanId();

        SpanData outer = humboldt.inMemorySpanExporter().getFinishedSpans().getFirst();
        assertEquals("outer", outer.name());
        assertEquals(outer.spanContext().getSpanId(), injectedId);
    }

    @Test
    void injectedTracerRecordsSpans() {
        container.select(TracedService.class).get().manualSpan("manual");

        assertTrue(humboldt.inMemorySpanExporter().getFinishedSpans().stream()
                .anyMatch(span -> span.name().equals("manual")));
    }

    @Test
    void producedOpenTelemetryIsTheInstalledOne() {
        OpenTelemetry produced = container.select(OpenTelemetry.class).get();
        assertSame(humboldt.getTracerProvider(), produced.getTracerProvider());
    }
}
