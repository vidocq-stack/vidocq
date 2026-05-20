package io.vidocq.humboldt;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Façade publique de Humboldt, point d'entrée du runtime MicroProfile Telemetry 2.1.
 *
 * <p>Stub M0 — la surface fonctionnelle (Tracer/Meter/Logger fournis par les SDKs)
 * arrive en M1.</p>
 */
public final class Humboldt {

    private static final Logger LOG = System.getLogger(Humboldt.class.getName());
    private static final String VERSION = "0.1.0-SNAPSHOT";

    private Humboldt() {
        // façade statique
    }

    /**
     * @return la version du runtime Humboldt embarqué.
     */
    public static String version() {
        return VERSION;
    }

    /**
     * Initialise le runtime Humboldt avec la configuration par défaut résolue depuis
     * les variables d'environnement {@code OTEL_*} et {@code MP_TELEMETRY_*}.
     *
     * <p>Stub M0 : émet juste un log d'init. L'implémentation effective est en M1.</p>
     */
    public static void start() {
        LOG.log(Level.INFO, "Humboldt {0} — init stub (M0)", VERSION);
    }
}
