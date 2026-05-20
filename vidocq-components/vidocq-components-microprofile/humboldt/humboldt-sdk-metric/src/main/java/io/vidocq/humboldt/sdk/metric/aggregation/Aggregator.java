package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.PointData;

import java.util.List;

/**
 * Storage par instrument : reçoit les enregistrements à chaud
 * ({@link #recordLong(long, Attributes)} / {@link #recordDouble(double, Attributes)})
 * et produit un snapshot à la collecte ({@link #collect(long, long)}).
 *
 * <p>Implémentations CUMULATIVE — l'état accumulé persiste entre les collectes,
 * la valeur exportée est cumulée depuis le démarrage.</p>
 *
 * @param <P> type de point produit (LongPointData pour Sum, HistogramPointData pour Histogram, ...)
 */
public interface Aggregator<P extends PointData> {

    /** Enregistre une valeur long avec les attributs associés. */
    default void recordLong(long value, Attributes attributes) {
        recordDouble((double) value, attributes);
    }

    /** Enregistre une valeur double avec les attributs associés. */
    default void recordDouble(double value, Attributes attributes) {
        recordLong((long) value, attributes);
    }

    /**
     * Produit un snapshot des points accumulés depuis le démarrage.
     *
     * @param startEpochNanos start time du SdkMeterProvider (fixe pour CUMULATIVE)
     * @param epochNanos      timestamp de la collecte courante
     * @return liste immutable des points par attribut-set
     */
    List<P> collect(long startEpochNanos, long epochNanos);
}
