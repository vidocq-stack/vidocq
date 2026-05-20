package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

import java.util.List;

/**
 * Point de données pour un Histogram à buckets explicites.
 *
 * @param startEpochNanos       timestamp de début de la fenêtre cumulative
 * @param epochNanos            timestamp de la collecte
 * @param attributes            labels du point
 * @param sum                   somme cumulée des valeurs enregistrées
 * @param count                 nombre d'enregistrements
 * @param min                   minimum observé (NaN si jamais enregistré)
 * @param max                   maximum observé (NaN si jamais enregistré)
 * @param boundaries            bornes explicites (taille n)
 * @param bucketCounts          counts par bucket (taille n+1)
 */
public record HistogramPointData(
        long startEpochNanos,
        long epochNanos,
        Attributes attributes,
        double sum,
        long count,
        double min,
        double max,
        List<Double> boundaries,
        List<Long> bucketCounts) implements PointData {

    public HistogramPointData {
        if (attributes == null) attributes = Attributes.empty();
        if (boundaries == null) boundaries = List.of();
        if (bucketCounts == null) bucketCounts = List.of();
        boundaries = List.copyOf(boundaries);
        bucketCounts = List.copyOf(bucketCounts);
        if (bucketCounts.size() != boundaries.size() + 1) {
            throw new IllegalArgumentException(
                    "bucketCounts.size() doit être boundaries.size()+1 : "
                            + bucketCounts.size() + " vs " + boundaries.size());
        }
    }
}
