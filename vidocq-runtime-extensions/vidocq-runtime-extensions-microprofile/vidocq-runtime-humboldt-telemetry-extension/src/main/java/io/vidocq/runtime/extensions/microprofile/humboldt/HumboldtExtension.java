/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.microprofile.humboldt;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Vidocq Runtime extension which connects Humboldt (MicroProfile Telemetry 2.1) to the
 * Vidocq life cycle.
 *
 * <p>Priority <b>100</b> — starts BEFORE application extensions (cassini=500,
 * cyrano, mansart, etc.) so that {@code GlobalOpenTelemetry} is set and
 * ready to receive spans from the first call.</p>
 *
 * <h3>Configuration</h3>
 * <p>Reads all standard OTel env vars via {@link VidocqConfiguration}:</p>
 * <ul>
 *   <li>{@code OTEL_SERVICE_NAME} — default {@code "vidocq-app"}</li>
 *   <li>{@code OTEL_RESOURCE_ATTRIBUTES} — {@code k=v} pairs separated by commas</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} — default {@code http://localhost:4318}</li>
 *   <li>{@code OTEL_TRACES_EXPORTER} / {@code _METRICS_EXPORTER} / {@code _LOGS_EXPORTER}
 *       ∈ {@code otlp | none | in-memory | logging}</li>
 *   <li>{@code OTEL_TRACES_SAMPLER} + {@code _ARG} (see {@code humboldt-runtime})</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_HEADERS} — auth Bearer, etc.</li>
 * </ul>
 *
 * <h3>Deactivation</h3>
 * <p>{@code MP_TELEMETRY_SDK_DISABLED=true} → the extension log a message and
 * does not install anything (useful for tests that want to force an OpenTelemetry
 * custom via {@code GlobalOpenTelemetry.set} manual).</p>
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
                    "Humboldt disabled via MP_TELEMETRY_SDK_DISABLED=true - "
                            + "GlobalOpenTelemetry will not be installed");
            return;
        }

        EnvConfig env = bridgeFromVidocqConfig(cfg);
        humboldt = HumboldtAutoConfigure.configure(env);

        // Publishing the CDI bean before addBeanClass — pattern MansartPoolHolder.
        // Apps/tests can then do @Inject AutoConfiguredHumboldt.
        HumboldtHolder.INSTANCE = humboldt;
        builder.addBeanClass(HumboldtHolder.class);

        try {
            GlobalOpenTelemetry.set(humboldt);
            LOG.log(Level.INFO,
                    "Humboldt installed as GlobalOpenTelemetry (service.name=" +
                            env.getOrDefault("OTEL_SERVICE_NAME", "vidocq-app") + ")");
        } catch (IllegalStateException already) {
            LOG.log(Level.WARNING,
                    "GlobalOpenTelemetry is already set by another component - "
                            + "Humboldt remains active as a local SDK but will not be the global one");
        }
    }

    @Override
    public void onStart(ExtensionContext context) {
        if (humboldt != null) {
            LOG.log(Level.INFO,
                    "Humboldt ready: OTLP traces/metrics/logs pipeline active "
                            + "(propagators={0})",
                    humboldt.getPropagators().getTextMapPropagator().fields());
        }
    }

    @Override
    public void onStop() {
        if (humboldt == null) return;
        LOG.log(Level.INFO, "Shutting down Humboldt - flushing spans/metrics/logs");
        humboldt.flush().join(5, TimeUnit.SECONDS);
        humboldt.shutdown().join(5, TimeUnit.SECONDS);
        HumboldtHolder.INSTANCE = null;
    }

    /**
     * Bridge Vidocq → EnvConfig — OTel keys are resolved via
     * {@link VidocqConfiguration} (which consults system properties + env vars +
     * vidocq.properties) rather than via {@code System.getenv()} directly.
     *
     * <p>Implementation: capture OTEL_MP_TELEMETRY_* keys on demand
     * via a Map populated by calls to the constructor.</p>
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

    /** Standard OTel/MP-Telemetry keys recognized by Humboldt autoconfig. */
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
            // OTEL_SDK_DISABLED gates the whole SDK in HumboldtAutoConfigure
            // (env.getBoolean("OTEL_SDK_DISABLED", true) — disabled by default per
            // MP Telemetry 2.1). Without bridging it, the SDK could never be enabled
            // through VidocqConfiguration and always booted as a no-op.
            "OTEL_SDK_DISABLED",
    };
}
