/**
 * Vidocq Runtime Telemetry extension — branche Humboldt (MicroProfile Telemetry 2.1)
 * sur le cycle de vie Vidocq.
 *
 * <p>Auto-config via env vars {@code OTEL_*} et system properties {@code otel.*},
 * installation de {@code GlobalOpenTelemetry}, shutdown ordonné en fin de vie.</p>
 *
 * <p>Découverte via ServiceLoader (META-INF/services + {@code provides} JPMS).</p>
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

    // Export du package pour permettre @Inject AutoConfiguredHumboldt depuis les apps/tests
    // (HumboldtHolder doit être accessible au container CDI Vauban).
    exports io.vidocq.runtime.ext.humboldt;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.ext.humboldt.HumboldtExtension;
}
