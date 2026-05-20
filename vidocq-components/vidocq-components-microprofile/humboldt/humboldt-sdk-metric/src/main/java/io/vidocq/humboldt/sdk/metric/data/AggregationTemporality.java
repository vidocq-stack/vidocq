package io.vidocq.humboldt.sdk.metric.data;

/**
 * Temporality OTel — {@link #CUMULATIVE} envoie l'état total depuis le
 * démarrage, {@link #DELTA} envoie le delta depuis la dernière collecte.
 *
 * <p>M4 MVP : Humboldt n'émet que CUMULATIVE. DELTA = M4b.</p>
 */
public enum AggregationTemporality {
    DELTA,
    CUMULATIVE
}
