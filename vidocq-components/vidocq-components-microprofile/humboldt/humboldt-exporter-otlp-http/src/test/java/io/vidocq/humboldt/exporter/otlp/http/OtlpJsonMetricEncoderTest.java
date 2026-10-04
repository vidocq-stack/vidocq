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

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonMetricEncoder;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpJsonMetricEncoderTest {

    @Test
    void encodes_non_finite_histogram_doubles_as_json_strings() {
        HistogramPointData point = new HistogramPointData(
                1L, 2L, Attributes.empty(),
                Double.POSITIVE_INFINITY, 2L, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                List.of(10.0), List.of(1L, 1L));
        MetricData histogram = new MetricData(
                Resource.empty(), InstrumentationScope.of("x"), "latency", "", "ms",
                InstrumentType.HISTOGRAM, AggregationTemporality.CUMULATIVE, false, List.of(point));

        String json = OtlpJsonMetricEncoder.encode(List.of(histogram));

        assertTrue(json.contains("\"sum\":\"Infinity\""), json);
        assertTrue(json.contains("\"min\":\"-Infinity\""), json);
        assertTrue(json.contains("\"max\":\"Infinity\""), json);
        assertTrue(json.contains("\"explicitBounds\":[10.0]"), json);
    }
}
