/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData;
import io.opentelemetry.sdk.resources.Resource;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;

import java.util.ArrayList;
import java.util.List;

/**
 * Convertit un {@link MetricData humboldt MetricData} en
 * {@link io.opentelemetry.sdk.metrics.data.MetricData OTel MetricData} pour le passage
 * aux {@code InMemoryMetricExporter} TCK qui assertent sur le format OTel SDK.
 *
 * <p>M4 supportés : Sum (COUNTER/UP_DOWN_COUNTER) avec PointData Long + Histogram avec
 * PointData Histogram. Les types Double/Gauge/Observable seront ajoutés au fur et à
 * mesure que humboldt-sdk-metric les supporte (M4b).</p>
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
            case COUNTER, UP_DOWN_COUNTER -> {
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
            default -> throw new UnsupportedOperationException(
                    "MetricDataMapper M4b : instrumentType " + humboldt.instrumentType()
                            + " pas encore supporté côté bridge OTel (Gauge/Observable à venir)");
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

    /** Utility — non utilisé directement mais conservé pour symétrie d'API. */
    static List<io.opentelemetry.sdk.metrics.data.MetricData> toOtelAll(java.util.Collection<MetricData> humboldts) {
        var out = new ArrayList<io.opentelemetry.sdk.metrics.data.MetricData>(humboldts.size());
        for (MetricData m : humboldts) {
            try { out.add(toOtel(m)); }
            catch (RuntimeException ignored) {}
        }
        return out;
    }
}
