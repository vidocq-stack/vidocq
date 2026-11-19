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

import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData;
import io.opentelemetry.sdk.resources.Resource;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.data.PointData;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link MetricData Humboldt MetricData} into
 * {@link io.opentelemetry.sdk.metrics.data.MetricData OTel MetricData} for passing
 * to OTel-SDK-based exporters (such as the MicroProfile Telemetry TCK
 * {@code InMemoryMetricExporter}) that assert on the OTel SDK format.
 *
 * <p>Currently supports: Sum (COUNTER/UP_DOWN_COUNTER, long and double points),
 * Histogram, and Gauge (long and double points). Remaining types will be added
 * as humboldt-sdk-metric supports them.</p>
 */
public final class MetricDataMapper {

    private MetricDataMapper() {}

    public static io.opentelemetry.sdk.metrics.data.MetricData toOtel(MetricData humboldt) {
        Resource resource = ResourceMapper.toOtel(humboldt.resource());
        InstrumentationScopeInfo scope = InstrumentationScopeInfo.builder(humboldt.scope().name())
                .setVersion(humboldt.scope().version())
                .build();
        String name = humboldt.name();
        String description = humboldt.description();
        String unit = humboldt.unit();
        AggregationTemporality temporality = humboldt.temporality()
                == io.vidocq.humboldt.sdk.metric.data.AggregationTemporality.CUMULATIVE
                ? AggregationTemporality.CUMULATIVE : AggregationTemporality.DELTA;

        List<? extends PointData> points = humboldt.points();

        return switch (humboldt.instrumentType()) {
            // Long or Double is decided by the type of the first point; points of the other type are dropped
            case COUNTER, UP_DOWN_COUNTER, OBSERVABLE_COUNTER, OBSERVABLE_UP_DOWN_COUNTER -> isDouble(points)
                    ? ImmutableMetricData.createDoubleSum(resource, scope, name, description, unit,
                            ImmutableSumData.create(humboldt.monotonic(), temporality, doublePoints(points)))
                    : ImmutableMetricData.createLongSum(resource, scope, name, description, unit,
                            ImmutableSumData.create(humboldt.monotonic(), temporality, longPoints(points)));
            case HISTOGRAM -> ImmutableMetricData.createDoubleHistogram(resource, scope, name, description, unit,
                    ImmutableHistogramData.create(temporality, histogramPoints(points)));
            case GAUGE, OBSERVABLE_GAUGE -> isDouble(points)
                    ? ImmutableMetricData.createDoubleGauge(resource, scope, name, description, unit,
                            ImmutableGaugeData.create(doublePoints(points)))
                    : ImmutableMetricData.createLongGauge(resource, scope, name, description, unit,
                            ImmutableGaugeData.create(longPoints(points)));
            default -> throw new UnsupportedOperationException(
                    "MetricDataMapper: instrumentType " + humboldt.instrumentType()
                            + " not yet supported on OTel bridge side (Observable pending)");
        };
    }

    private static boolean isDouble(List<? extends PointData> points) {
        return !points.isEmpty() && points.getFirst() instanceof DoublePointData;
    }

    private static List<io.opentelemetry.sdk.metrics.data.DoublePointData> doublePoints(
            List<? extends PointData> points) {
        var out = new ArrayList<io.opentelemetry.sdk.metrics.data.DoublePointData>(points.size());
        for (PointData p : points) {
            if (p instanceof DoublePointData(var start, var epoch, var attributes, var value)) {
                out.add(ImmutableDoublePointData.create(start, epoch, attributes, value));
            }
        }
        return out;
    }

    private static List<io.opentelemetry.sdk.metrics.data.LongPointData> longPoints(List<? extends PointData> points) {
        var out = new ArrayList<io.opentelemetry.sdk.metrics.data.LongPointData>(points.size());
        for (PointData p : points) {
            if (p instanceof LongPointData(var start, var epoch, var attributes, var value)) {
                out.add(ImmutableLongPointData.create(start, epoch, attributes, value));
            }
        }
        return out;
    }

    private static List<io.opentelemetry.sdk.metrics.data.HistogramPointData> histogramPoints(
            List<? extends PointData> points) {
        var out = new ArrayList<io.opentelemetry.sdk.metrics.data.HistogramPointData>(points.size());
        for (PointData p : points) {
            if (p instanceof HistogramPointData(var start, var epoch, var attributes, var sum, _,
                    var min, var max, var boundaries, var bucketCounts)) {
                out.add(ImmutableHistogramPointData.create(start, epoch, attributes,
                        sum, !Double.isNaN(min), min, !Double.isNaN(max), max, boundaries, bucketCounts));
            }
        }
        return out;
    }

    /** Mapper humboldt.Resource → OTel Resource. */
    private static final class ResourceMapper {
        static Resource toOtel(io.vidocq.humboldt.sdk.common.Resource humboldt) {
            return humboldt.schemaUrl() != null
                    ? Resource.create(humboldt.attributes(), humboldt.schemaUrl())
                    : Resource.create(humboldt.attributes());
        }
    }

    /** Bulk variant — maps every convertible metric, silently skipping unsupported ones. */
    public static List<io.opentelemetry.sdk.metrics.data.MetricData> toOtelAll(java.util.Collection<MetricData> humboldts) {
        var out = new ArrayList<io.opentelemetry.sdk.metrics.data.MetricData>(humboldts.size());
        for (MetricData m : humboldts) {
            try { out.add(toOtel(m)); }
            catch (RuntimeException _) { /* unsupported metric type: skipped */ }
        }
        return out;
    }
}
