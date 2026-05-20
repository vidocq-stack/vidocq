/**
 * Humboldt CDI — interceptor {@code @WithSpan} pour instrumenter les méthodes
 * applicatives de tracing OpenTelemetry sans réécriture manuelle.
 *
 * <p>M6a MVP : annotation {@code @WithSpan} Humboldt + {@code WithSpanInterceptor}
 * compatible CDI 4.1 Lite. Validation runtime avec Vauban prévue en M6b/M7.</p>
 *
 * <p>Future alignement (M7) : remap éventuel sur l'annotation officielle
 * {@code org.eclipse.microprofile.telemetry.tracing.WithSpan} selon les
 * exigences du TCK MicroProfile Telemetry 2.1.</p>
 */
module io.vidocq.humboldt.cdi {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires transitive jakarta.cdi;
    requires transitive jakarta.interceptor;
    requires java.logging;

    exports io.vidocq.humboldt.cdi;
}
