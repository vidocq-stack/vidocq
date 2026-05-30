package io.vidocq.humboldt;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Public facade of Humboldt, the entry point for the MicroProfile Telemetry 2.1 runtime.
 *
 * <p>M0 stub — the functional surface (Tracer/Meter/Logger provided by the SDKs)
 * arrives in M1.</p>
 */
public final class Humboldt {

    private static final Logger LOG = System.getLogger(Humboldt.class.getName());
    private static final String VERSION = "0.1.0-SNAPSHOT";

    private Humboldt() {
        // static facade
    }

    /**
     * @return the embedded Humboldt runtime version.
     */
    public static String version() {
        return VERSION;
    }

    /**
     * Initializes the Humboldt runtime with the default configuration resolved from
     * the {@code OTEL_*} and {@code MP_TELEMETRY_*} environment variables.
     *
     * <p>M0 stub: only emits an init log. The effective implementation arrives in M1.</p>
     */
    public static void start() {
        LOG.log(Level.INFO, "Humboldt {0} — init stub (M0)", VERSION);
    }
}
