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
import io.vidocq.humboldt.otel.interop.OtelSpiAutoConfiguration;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Locale;
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
        // The holder bean is registered now; the SDK instance is built in
        // onStart — OTel SPI providers may resolve their collaborators through
        // CDI.current() (the MP Telemetry TCK's InMemorySpanExporterProvider
        // does), which requires the container to be up.
        builder.addBeanClass(HumboldtHolder.class);
    }

    @Override
    public void onStart(ExtensionContext context) {
        if (disabled) {
            return;
        }
        Map<String, String> otelEnv = bridgeFromVidocqConfig(cfg);
        // Discover OTel SDK autoconfigure SPI providers (span/metric exporters,
        // samplers, propagators, resources, customizers) on the deployment's TCCL
        // and bridge them to the Humboldt SDK. This is how the MP Telemetry TCK
        // (and any OTel-SDK-aware deployment) plugs its in-memory exporters in.
        OtelSpiAutoConfiguration.Result spi = OtelSpiAutoConfiguration.discover(
                otelEnv, Thread.currentThread().getContextClassLoader());
        EnvConfig env = EnvConfig.of(spi.env(), Map.of());
        humboldt = HumboldtAutoConfigure.configure(
                env,
                spi.extraSpanExporters(),
                spi.samplerOverride(),
                spi.propagatorsOverride(),
                spi.extraMetricExporters());

        // Apps/tests can @Inject AutoConfiguredHumboldt through the holder bean.
        HumboldtHolder.INSTANCE = humboldt;

        try {
            GlobalOpenTelemetry.set(humboldt);
            LOG.log(Level.INFO,
                    "Humboldt installed as GlobalOpenTelemetry (service.name=" +
                            env.getOrDefault("OTEL_SERVICE_NAME", "vidocq-app") + ")");
        } catch (IllegalStateException already) {
            // Sequential boots in the same JVM (e.g. TCK deployments) hit the
            // set-once guard of GlobalOpenTelemetry: reset and retry ONCE. In
            // production the set happens a single time and this path is never taken.
            try {
                GlobalOpenTelemetry.resetForTest();
                GlobalOpenTelemetry.set(humboldt);
                LOG.log(Level.WARNING,
                        "GlobalOpenTelemetry was already set (previous boot in this JVM) - "
                                + "replaced it with this Humboldt instance");
            } catch (IllegalStateException stillSet) {
                LOG.log(Level.WARNING,
                        "GlobalOpenTelemetry is already set by another component - "
                                + "Humboldt remains active as a local SDK but will not be the global one");
            }
        }
        LOG.log(Level.INFO,
                "Humboldt ready: traces/metrics/logs pipeline active (propagators={0})",
                humboldt.getPropagators().getTextMapPropagator().fields());
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
     * Bridge Vidocq → OTel env map — OTel keys are resolved via
     * {@link VidocqConfiguration} (which consults system properties + env vars +
     * vidocq.properties) rather than via {@code System.getenv()} directly.
     *
     * <p>Both forms are consulted for each bridged key: the property form
     * ({@code otel.traces.exporter}) first, then the env form
     * ({@code OTEL_TRACES_EXPORTER}) which takes priority — mirroring
     * {@link EnvConfig}'s env-over-property precedence. The result is a single
     * env-form map consumable by both
     * {@link OtelSpiAutoConfiguration#discover(Map, ClassLoader)} and
     * {@link EnvConfig#of(Map, Map)}.</p>
     */
    private static Map<String, String> bridgeFromVidocqConfig(VidocqConfiguration cfg) {
        Map<String, String> env = new LinkedHashMap<>();
        // Arbitrary otel.* properties first (lowest precedence): OTel SPI
        // providers may consult application-defined keys (the MP Telemetry TCK's
        // TestResourceProvider reads otel.test.*), and OTel semantics accept any
        // otel.* property.
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("otel.")) {
                env.put(name.toUpperCase(Locale.ROOT).replace('.', '_'),
                        System.getProperty(name));
            }
        }
        for (String key : OTEL_KEYS_TO_BRIDGE) {
            String propKey = key.toLowerCase(Locale.ROOT).replace('_', '.');
            cfg.property(propKey).ifPresent(v -> env.put(key, v));
            cfg.property(key).ifPresent(v -> env.put(key, v));
        }
        return env;
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
            // Propagator selection (tracecontext, baggage, b3, b3multi, jaeger or an
            // SPI-provided name) — consumed by OtelSpiAutoConfiguration + Humboldt.
            "OTEL_PROPAGATORS",
            // MP Telemetry §3.3 alias for OTEL_PROPAGATORS.
            "MP_TELEMETRY_PROPAGATORS",
            // Metric reader flush interval (e.g. the TCK shortens it for assertions).
            "OTEL_METRIC_EXPORT_INTERVAL",
            "MP_TELEMETRY_SDK_DISABLED",
            // OTEL_SDK_DISABLED gates the whole SDK in HumboldtAutoConfigure
            // (env.getBoolean("OTEL_SDK_DISABLED", true) — disabled by default per
            // MP Telemetry 2.1). Without bridging it, the SDK could never be enabled
            // through VidocqConfiguration and always booted as a no-op.
            "OTEL_SDK_DISABLED",
    };
}
