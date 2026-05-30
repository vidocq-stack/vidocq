/**
 * Humboldt SDK Common — shared building blocks for {@code humboldt-sdk-trace},
 * {@code humboldt-sdk-metric}, and {@code humboldt-sdk-log}.
 *
 * <ul>
 *   <li>{@link io.vidocq.humboldt.sdk.common.Clock} — wall + monotonic clock
 *   <li>{@link io.vidocq.humboldt.sdk.common.IdGenerator} — 128-bit traceId, 64-bit spanId
 *   <li>{@link io.vidocq.humboldt.sdk.common.Resource} — telemetry source attributes
 * </ul>
 */
module io.vidocq.humboldt.sdk.common {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.opentelemetry.api;

    exports io.vidocq.humboldt.sdk.common;
}
