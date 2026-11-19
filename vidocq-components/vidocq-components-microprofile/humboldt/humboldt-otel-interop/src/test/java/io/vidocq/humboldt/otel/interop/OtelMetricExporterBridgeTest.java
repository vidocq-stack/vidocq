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
package io.vidocq.humboldt.otel.interop;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelMetricExporterBridgeTest {

    /** OTel metric exporter whose every operation answers the result built by {@code results}. */
    private static final class ScriptedExporter implements MetricExporter {
        private final Supplier<CompletableResultCode> results;

        ScriptedExporter(Supplier<CompletableResultCode> results) {
            this.results = results;
        }

        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            return results.get();
        }

        @Override
        public CompletableResultCode flush() {
            return results.get();
        }

        @Override
        public CompletableResultCode shutdown() {
            return results.get();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return AggregationTemporality.CUMULATIVE;
        }
    }

    private static io.vidocq.humboldt.sdk.metric.data.MetricData counter(long value) {
        return new io.vidocq.humboldt.sdk.metric.data.MetricData(null, null, "hits", null, null,
                io.vidocq.humboldt.sdk.metric.data.InstrumentType.COUNTER, null, true,
                List.of(new LongPointData(1L, 2L, Attributes.empty(), value)));
    }

    @Test
    void exports_mapped_metrics_to_the_otel_exporter() {
        InMemoryMetricExporter otel = InMemoryMetricExporter.create();
        OtelMetricExporterBridge bridge = new OtelMetricExporterBridge(otel);

        assertTrue(bridge.export(List.of(counter(5L))).isSuccess());

        List<MetricData> exported = otel.getFinishedMetricItems();
        assertEquals(1, exported.size());
        assertEquals("hits", exported.get(0).getName());
        assertEquals(5L, exported.get(0).getLongSumData().getPoints().iterator().next().getValue());
        assertTrue(bridge.flush().isSuccess());
        assertTrue(bridge.shutdown().isSuccess());
    }

    @Test
    void failed_otel_results_are_failures() {
        OtelMetricExporterBridge bridge = new OtelMetricExporterBridge(
                new ScriptedExporter(CompletableResultCode::ofFailure));
        assertFalse(bridge.export(List.of()).isSuccess());
        assertFalse(bridge.flush().isSuccess());
        assertFalse(bridge.shutdown().isSuccess());
    }

    @Test
    void pending_otel_results_are_awaited() {
        OtelMetricExporterBridge succeeding = new OtelMetricExporterBridge(new ScriptedExporter(() -> {
            CompletableResultCode code = new CompletableResultCode();
            Thread.ofVirtual().start(code::succeed);
            return code;
        }));
        assertTrue(succeeding.flush().isSuccess());

        OtelMetricExporterBridge failing = new OtelMetricExporterBridge(new ScriptedExporter(() -> {
            CompletableResultCode code = new CompletableResultCode();
            Thread.ofVirtual().start(code::fail);
            return code;
        }));
        assertFalse(failing.shutdown().isSuccess());
    }
}
