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
package io.vidocq.humboldt.tck.arquillian;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link MetricData humboldt MetricData} into
 * {@link io.opentelemetry.sdk.metrics.data.MetricData OTel MetricData} for passing
 * to TCK {@code InMemoryMetricExporter}s that assert on the OTel SDK format.
 *
 * <p>M4 currently supports: Sum (COUNTER/UP_DOWN_COUNTER) with Long PointData + Histogram with
 * Histogram PointData. Double/Gauge/Observable types will be added progressively
 * as humboldt-sdk-metric supports them (M4b).</p>
 */
final class MetricDataMapper {

    private MetricDataMapper() {}

    static io.opentelemetry.sdk.metrics.data.MetricData toOtel(MetricData humboldt) {
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

        return switch (humboldt.instrumentType()) {
            case COUNTER, UP_DOWN_COUNTER, OBSERVABLE_COUNTER, OBSERVABLE_UP_DOWN_COUNTER -> {
                // Determine Long or Double based on the type of the actual points
                boolean isDouble = !humboldt.points().isEmpty()
                        && humboldt.points().get(0) instanceof DoublePointData;
                if (isDouble) {
                    var points = new ArrayList<io.opentelemetry.sdk.metrics.data.DoublePointData>(humboldt.points().size());
                    for (var p : humboldt.points()) {
                        if (p instanceof DoublePointData dp) {
                            points.add(ImmutableDoublePointData.create(
                                    dp.startEpochNanos(), dp.epochNanos(), dp.attributes(), dp.value()));
                        }
                    }
                    yield ImmutableMetricData.createDoubleSum(resource, scope, name, description, unit,
                            ImmutableSumData.create(humboldt.monotonic(), temporality, points));
                } else {
                    var points = new ArrayList<io.opentelemetry.sdk.metrics.data.LongPointData>(humboldt.points().size());
                    for (var p : humboldt.points()) {
                        if (p instanceof LongPointData lp) {
                            points.add(ImmutableLongPointData.create(
                                    lp.startEpochNanos(), lp.epochNanos(), lp.attributes(), lp.value()));
                        }
                    }
                    yield ImmutableMetricData.createLongSum(resource, scope, name, description, unit,
                            ImmutableSumData.create(humboldt.monotonic(), temporality, points));
                }
            }
            case HISTOGRAM -> {
                var points = new ArrayList<io.opentelemetry.sdk.metrics.data.HistogramPointData>(humboldt.points().size());
                for (var p : humboldt.points()) {
                    if (p instanceof HistogramPointData hp) {
                        points.add(ImmutableHistogramPointData.create(
                                hp.startEpochNanos(), hp.epochNanos(), hp.attributes(),
                                hp.sum(), !Double.isNaN(hp.min()), hp.min(),
                                !Double.isNaN(hp.max()), hp.max(),
                                hp.boundaries(), hp.bucketCounts()));
                    }
                }
                yield ImmutableMetricData.createDoubleHistogram(resource, scope, name, description, unit,
                        ImmutableHistogramData.create(temporality, points));
            }
            case GAUGE, OBSERVABLE_GAUGE -> {
                boolean isDouble = !humboldt.points().isEmpty()
                        && humboldt.points().get(0) instanceof DoublePointData;
                if (isDouble) {
                    var points = new ArrayList<io.opentelemetry.sdk.metrics.data.DoublePointData>(humboldt.points().size());
                    for (var p : humboldt.points()) {
                        if (p instanceof DoublePointData dp) {
                            points.add(ImmutableDoublePointData.create(
                                    dp.startEpochNanos(), dp.epochNanos(), dp.attributes(), dp.value()));
                        }
                    }
                    yield ImmutableMetricData.createDoubleGauge(resource, scope, name, description, unit,
                            ImmutableGaugeData.create(points));
                } else {
                    var points = new ArrayList<io.opentelemetry.sdk.metrics.data.LongPointData>(humboldt.points().size());
                    for (var p : humboldt.points()) {
                        if (p instanceof LongPointData lp) {
                            points.add(ImmutableLongPointData.create(
                                    lp.startEpochNanos(), lp.epochNanos(), lp.attributes(), lp.value()));
                        }
                    }
                    yield ImmutableMetricData.createLongGauge(resource, scope, name, description, unit,
                            ImmutableGaugeData.create(points));
                }
            }
            default -> throw new UnsupportedOperationException(
                    "MetricDataMapper : instrumentType " + humboldt.instrumentType()
                            + " not yet supported on OTel bridge side (Observable pending)");
        };
    }

    /** Mapper humboldt.Resource → OTel Resource. */
    private static final class ResourceMapper {
        static Resource toOtel(io.vidocq.humboldt.sdk.common.Resource humboldt) {
            return humboldt.schemaUrl() != null
                    ? Resource.create(humboldt.attributes(), humboldt.schemaUrl())
                    : Resource.create(humboldt.attributes());
        }
    }

    /** Utility — not used directly but kept for API symmetry. */
    static List<io.opentelemetry.sdk.metrics.data.MetricData> toOtelAll(java.util.Collection<MetricData> humboldts) {
        var out = new ArrayList<io.opentelemetry.sdk.metrics.data.MetricData>(humboldts.size());
        for (MetricData m : humboldts) {
            try { out.add(toOtel(m)); }
            catch (RuntimeException ignored) {}
        }
        return out;
    }
}
