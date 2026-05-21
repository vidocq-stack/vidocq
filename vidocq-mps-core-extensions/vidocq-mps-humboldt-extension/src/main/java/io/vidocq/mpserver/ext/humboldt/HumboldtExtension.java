package io.vidocq.mpserver.ext.humboldt;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqConfiguration;
import io.vidocq.mpserver.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Extension Vidocq-MPS qui branche Humboldt (MicroProfile Telemetry 2.1) sur le
 * cycle de vie Vidocq.
 *
 * <p>Priorité <b>100</b> — démarre AVANT les extensions applicatives (cassini=500,
 * cyrano, mansart, etc.) pour que {@code GlobalOpenTelemetry} soit set et
 * prêt à recevoir les spans dès le premier appel.</p>
 *
 * <h3>Configuration</h3>
 * <p>Lit toutes les env vars OTel standard via {@link VidocqConfiguration} :</p>
 * <ul>
 *   <li>{@code OTEL_SERVICE_NAME} — défaut {@code "vidocq-app"}</li>
 *   <li>{@code OTEL_RESOURCE_ATTRIBUTES} — paires {@code k=v} séparées par virgule</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} — défaut {@code http://localhost:4318}</li>
 *   <li>{@code OTEL_TRACES_EXPORTER} / {@code _METRICS_EXPORTER} / {@code _LOGS_EXPORTER}
 *       ∈ {@code otlp | none | in-memory | logging}</li>
 *   <li>{@code OTEL_TRACES_SAMPLER} + {@code _ARG} (cf. {@code humboldt-runtime})</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_HEADERS} — auth Bearer, etc.</li>
 * </ul>
 *
 * <h3>Désactivation</h3>
 * <p>{@code MP_TELEMETRY_SDK_DISABLED=true} → l'extension log un message et
 * n'installe rien (utile pour les tests qui veulent forcer un OpenTelemetry
 * custom via {@code GlobalOpenTelemetry.set} manuel).</p>
 */
public final class HumboldtExtension implements VidocqExtension {

    private static final Logger LOG = System.getLogger(HumboldtExtension.class.getName());

    private VidocqConfiguration cfg;
    private AutoConfiguredHumboldt humboldt;
    private boolean disabled;

    @Override
    public String name() {
        return "humboldt-telemetry";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.cfg = config;
        this.disabled = "true".equalsIgnoreCase(
                config.property("MP_TELEMETRY_SDK_DISABLED", "false"));
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        if (disabled) {
            LOG.log(Level.INFO,
                    "Humboldt désactivé via MP_TELEMETRY_SDK_DISABLED=true — "
                            + "aucune installation de GlobalOpenTelemetry");
            return;
        }

        EnvConfig env = bridgeFromVidocqConfig(cfg);
        humboldt = HumboldtAutoConfigure.configure(env);

        // Publication du bean CDI avant addBeanClass — pattern MansartPoolHolder.
        // Les apps/tests peuvent ensuite faire @Inject AutoConfiguredHumboldt.
        HumboldtHolder.INSTANCE = humboldt;
        builder.addBeanClass(HumboldtHolder.class);

        try {
            GlobalOpenTelemetry.set(humboldt);
            LOG.log(Level.INFO,
                    "Humboldt installé comme GlobalOpenTelemetry (service.name=" +
                            env.getOrDefault("OTEL_SERVICE_NAME", "vidocq-app") + ")");
        } catch (IllegalStateException already) {
            LOG.log(Level.WARNING,
                    "GlobalOpenTelemetry déjà set par un autre composant — "
                            + "Humboldt reste actif comme SDK local mais ne sera pas le global");
        }
    }

    @Override
    public void onStart(ExtensionContext context) {
        if (humboldt != null) {
            LOG.log(Level.INFO,
                    "Humboldt prêt : traces/metrics/logs pipeline OTLP actif "
                            + "(propagators={0})",
                    humboldt.getPropagators().getTextMapPropagator().fields());
        }
    }

    @Override
    public void onStop() {
        if (humboldt == null) return;
        LOG.log(Level.INFO, "Shutdown Humboldt — flush des spans/metrics/logs en cours");
        humboldt.flush().join(5, TimeUnit.SECONDS);
        humboldt.shutdown().join(5, TimeUnit.SECONDS);
        HumboldtHolder.INSTANCE = null;
    }

    /**
     * Bridge Vidocq → EnvConfig — les clés OTel sont résolues via
     * {@link VidocqConfiguration} (qui consulte system properties + env vars +
     * vidocq.properties) plutôt que via {@code System.getenv()} direct.
     *
     * <p>Implémentation : capture les clés OTEL_MP_TELEMETRY_* à la demande
     * via une Map populée par les appels au constructor.</p>
     */
    private static EnvConfig bridgeFromVidocqConfig(VidocqConfiguration cfg) {
        Map<String, String> env = new HashMap<>();
        Map<String, String> props = new HashMap<>();
        for (String key : OTEL_KEYS_TO_BRIDGE) {
            cfg.property(key).ifPresent(v -> env.put(key, v));
            String propKey = key.toLowerCase().replace('_', '.');
            cfg.property(propKey).ifPresent(v -> props.put(propKey, v));
        }
        return EnvConfig.of(env, props);
    }

    /** Clés OTel/MP-Telemetry standard reconnues par l'autoconfig Humboldt. */
    private static final String[] OTEL_KEYS_TO_BRIDGE = {
            "OTEL_SERVICE_NAME",
            "OTEL_RESOURCE_ATTRIBUTES",
            "OTEL_EXPORTER_OTLP_ENDPOINT",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
            "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT",
            "OTEL_EXPORTER_OTLP_HEADERS",
            "OTEL_TRACES_EXPORTER",
            "OTEL_METRICS_EXPORTER",
            "OTEL_LOGS_EXPORTER",
            "OTEL_TRACES_SAMPLER",
            "OTEL_TRACES_SAMPLER_ARG",
            "MP_TELEMETRY_SDK_DISABLED",
    };
}
