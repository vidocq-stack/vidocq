package io.vidocq.humboldt.sdk.metric.data;

import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;

import java.util.List;

/**
 * Collected metric (snapshot ready to export) — immutable view produced by
 * {@code MetricReader.collect()} and consumed by {@code MetricExporter.export()}.
 *
 * <p>For M4 MVP, only Sum (Counter) and Histogram are supported (the
 * {@code points} field contains {@link LongPointData} or
 * {@link HistogramPointData}). The type is carried by {@link #instrumentType()}.</p>
 */
public record MetricData(
        Resource resource,
        InstrumentationScope scope,
        String name,
        String description,
        String unit,
        InstrumentType instrumentType,
        AggregationTemporality temporality,
        boolean monotonic,
        List<? extends PointData> points) {

    public MetricData {
        if (name == null) throw new NullPointerException("name");
        if (description == null) description = "";
        if (unit == null) unit = "";
        if (instrumentType == null) throw new NullPointerException("instrumentType");
        if (temporality == null) temporality = AggregationTemporality.CUMULATIVE;
        points = points == null ? List.of() : List.copyOf(points);
        if (resource == null) resource = Resource.empty();
        if (scope == null) scope = InstrumentationScope.of("");
    }
}
