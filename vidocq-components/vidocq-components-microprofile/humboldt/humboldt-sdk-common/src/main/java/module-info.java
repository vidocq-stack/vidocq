/**
 * Humboldt SDK Common — briques partagées entre {@code humboldt-sdk-trace},
 * {@code humboldt-sdk-metric} et {@code humboldt-sdk-log}.
 *
 * <ul>
 *   <li>{@link io.vidocq.humboldt.sdk.common.Clock} — horloge wall + monotonique
 *   <li>{@link io.vidocq.humboldt.sdk.common.IdGenerator} — traceId 128 bits, spanId 64 bits
 *   <li>{@link io.vidocq.humboldt.sdk.common.Resource} — attributs de la source télémétrique
 * </ul>
 */
module io.vidocq.humboldt.sdk.common {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.opentelemetry.api;

    exports io.vidocq.humboldt.sdk.common;
}
