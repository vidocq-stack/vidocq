/**
 * Vidocq-MPS Telemetry extension — branche Humboldt (MicroProfile Telemetry 2.1)
 * sur le cycle de vie Vidocq.
 *
 * <p>Auto-config via env vars {@code OTEL_*} et system properties {@code otel.*},
 * installation de {@code GlobalOpenTelemetry}, shutdown ordonné en fin de vie.</p>
 *
 * <p>Découverte via ServiceLoader (META-INF/services + {@code provides} JPMS).</p>
 */
module io.vidocq.mpserver.ext.humboldt {

    requires io.vidocq.mpserver.spi;
    requires io.vidocq.humboldt.runtime;
    requires io.vidocq.humboldt.sdk.common;
    requires io.vidocq.humboldt.api;
    requires io.vidocq.vauban.core;
    requires io.opentelemetry.api;
    requires java.logging;

    provides io.vidocq.mpserver.spi.VidocqExtension
            with io.vidocq.mpserver.ext.humboldt.HumboldtExtension;
}
