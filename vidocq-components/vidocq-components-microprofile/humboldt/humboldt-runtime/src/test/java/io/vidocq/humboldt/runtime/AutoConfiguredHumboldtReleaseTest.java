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

import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.Releasable;
import io.vidocq.humboldt.sdk.log.SdkLoggerProvider;
import io.vidocq.humboldt.sdk.metric.SdkMeterProvider;
import io.vidocq.humboldt.sdk.trace.BatchSpanProcessor;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Humboldt lets its exporters' connections go for a CRaC checkpoint, once what is queued is exported, and takes them
 * back after the restore; the providers, processors and exporters stay the same objects, so nothing that holds them
 * goes stale.
 */
class AutoConfiguredHumboldtReleaseTest {

    private final List<String> events = new CopyOnWriteArrayList<>();

    private Releasable releasable(String name) {
        return new Releasable() {
            @Override
            public void release() {
                events.add("release " + name);
            }

            @Override
            public void reopen() {
                events.add("reopen " + name);
            }
        };
    }

    private AutoConfiguredHumboldt humboldt(List<Releasable> releasables) {
        SpanExporter recording = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                events.add("export " + spans.size());
                return new CompletableResultCode().succeed();
            }

            @Override
            public CompletableResultCode flush() {
                return new CompletableResultCode().succeed();
            }

            @Override
            public CompletableResultCode shutdown() {
                return new CompletableResultCode().succeed();
            }
        };
        return new AutoConfiguredHumboldt(
                SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(BatchSpanProcessor.builder(recording).build())
                        .build(),
                SdkMeterProvider.builder().build(),
                SdkLoggerProvider.builder().build(),
                W3CPropagators.get(),
                InMemoryExporters.NONE,
                releasables);
    }

    @Test
    void releaseExportsWhatIsQueuedThenReleasesEveryExporter() {
        try (AutoConfiguredHumboldt h = humboldt(List.of(releasable("traces"), releasable("metrics")))) {
            h.getTracerProvider().get("release").spanBuilder("GET /todos").startSpan().end();

            h.release();

            assertEquals(List.of("export 1", "release traces", "release metrics"), events);
        }
    }

    @Test
    void reopenReopensEveryExporter() {
        try (AutoConfiguredHumboldt h = humboldt(List.of(releasable("traces"), releasable("metrics")))) {
            h.release();
            events.clear();

            h.reopen();

            assertEquals(List.of("reopen traces", "reopen metrics"), events);
        }
    }

    @Test
    void configureKeepsTheOtlpExportersToRelease() {
        Map<String, String> env = Map.of(
                "OTEL_SDK_DISABLED", "false",
                "OTEL_TRACES_EXPORTER", "otlp",
                "OTEL_METRICS_EXPORTER", "otlp",
                "OTEL_LOGS_EXPORTER", "none",
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9");
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            assertEquals(2, h.releasables().size(), "the OTLP span and metric exporters");
        }
    }

    @Test
    void configureWithoutAnOtlpExporterKeepsNothingToRelease() {
        Map<String, String> env = Map.of(
                "OTEL_SDK_DISABLED", "false",
                "OTEL_TRACES_EXPORTER", "in-memory",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none");
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(env, Map.of()))) {
            assertTrue(h.releasables().isEmpty());
            h.release();
            h.reopen();
        }
        try (AutoConfiguredHumboldt disabled = HumboldtAutoConfigure.configure(EnvConfig.of(Map.of(), Map.of()))) {
            assertTrue(disabled.releasables().isEmpty(), "the SDK is disabled by default");
        }
    }
}
