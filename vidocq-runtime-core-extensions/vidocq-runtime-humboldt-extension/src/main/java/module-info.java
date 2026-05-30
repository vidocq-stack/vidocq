/**
 * Vidocq Runtime Telemetry extension — Humboldt branch (MicroProfile Telemetry 2.1)
 * on the Vidocq life cycle.
 *
 * <p>Auto-config via env vars {@code OTEL_*} and system properties {@code otel.*},
 * installation of {@code GlobalOpenTelemetry}, shutdown ordered at end of life.</p>
 *
 * <p>Discovery via ServiceLoader (META-INF/services + {@code provides} JPMS).</p>
 */
module io.vidocq.runtime.ext.humboldt {

    requires io.vidocq.runtime.spi;
    requires io.vidocq.humboldt.runtime;
    requires io.vidocq.humboldt.sdk.common;
    requires io.vidocq.humboldt.api;
    requires io.vidocq.vauban.core;
    requires io.opentelemetry.api;
    requires jakarta.cdi;
    requires java.logging;

    // Export package to enable @Inject AutoConfiguredHumboldt from apps/tests
    // (HumboldtHolder must be accessible to the CDI Vauban container).
    exports io.vidocq.runtime.ext.humboldt;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.ext.humboldt.HumboldtExtension;
}
