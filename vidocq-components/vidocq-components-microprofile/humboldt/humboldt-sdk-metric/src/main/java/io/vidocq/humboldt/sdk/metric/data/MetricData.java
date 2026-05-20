package io.vidocq.humboldt.sdk.metric.data;

import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;

import java.util.List;

/**
 * Métrique collectée (snapshot prêt à exporter) — vue immutable produite par
 * {@code MetricReader.collect()} et consommée par {@code MetricExporter.export()}.
 *
 * <p>Pour M4 MVP, seuls Sum (Counter) et Histogram sont supportés (champ
 * {@code points} contient des {@link LongPointData} ou des
 * {@link HistogramPointData}). Le type est porté par {@link #instrumentType()}.</p>
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
