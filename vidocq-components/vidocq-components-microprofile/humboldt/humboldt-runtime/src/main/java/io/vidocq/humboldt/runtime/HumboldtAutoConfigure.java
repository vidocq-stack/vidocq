package io.vidocq.humboldt.runtime;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.exporter.otlp.http.OtlpHttpLogExporter;
import io.vidocq.humboldt.exporter.otlp.http.OtlpHttpMetricExporter;
import io.vidocq.humboldt.exporter.otlp.http.OtlpHttpSpanExporter;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.BatchLogRecordProcessor;
import io.vidocq.humboldt.sdk.log.SdkLoggerProvider;
import io.vidocq.humboldt.sdk.log.SimpleLogRecordProcessor;
import io.vidocq.humboldt.sdk.log.bridge.HumboldtJulHandler;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;
import io.vidocq.humboldt.sdk.log.export.LoggingLogRecordExporter;
import io.vidocq.humboldt.sdk.metric.PeriodicMetricReader;
import io.vidocq.humboldt.sdk.metric.SdkMeterProvider;
import io.vidocq.humboldt.sdk.metric.export.InMemoryMetricExporter;
import io.vidocq.humboldt.sdk.metric.export.MetricExporter;
import io.vidocq.humboldt.sdk.trace.BatchSpanProcessor;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.export.LoggingSpanExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Façade autoconfig — lit les env vars OTEL_* et assemble un
 * {@link AutoConfiguredHumboldt} prêt à l'emploi.
 *
 * <p>Env vars supportées (M6c MVP) :</p>
 * <ul>
 *   <li>{@code OTEL_SERVICE_NAME} — défaut {@code "humboldt"}</li>
 *   <li>{@code OTEL_RESOURCE_ATTRIBUTES} — paires {@code key=value} séparées par virgule</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} — défaut {@code http://localhost:4318}</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} / {@code _METRICS_ENDPOINT} / {@code _LOGS_ENDPOINT} — overrides per-signal</li>
 *   <li>{@code OTEL_TRACES_EXPORTER} / {@code OTEL_METRICS_EXPORTER} / {@code OTEL_LOGS_EXPORTER} — {@code otlp} | {@code none} | {@code in-memory} | {@code logging} (traces uniquement)</li>
 *   <li>{@code OTEL_TRACES_SAMPLER} — {@code always_on} | {@code always_off} | {@code traceidratio} | {@code parentbased_always_on}</li>
 *   <li>{@code OTEL_TRACES_SAMPLER_ARG} — ratio (double) pour {@code traceidratio}</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_HEADERS} — paires {@code key=value} séparées par virgule</li>
 * </ul>
 *
 * <p>Différé en M7 : {@code OTEL_EXPORTER_OTLP_TIMEOUT}, {@code OTEL_EXPORTER_OTLP_PROTOCOL},
 * {@code MP_TELEMETRY_SDK_DISABLED}, {@code MP_TELEMETRY_PROPAGATORS}.</p>
 */
public final class HumboldtAutoConfigure {

    private static final Logger LOG = System.getLogger(HumboldtAutoConfigure.class.getName());
    private static final String DEFAULT_ENDPOINT = "http://localhost:4318";

    private HumboldtAutoConfigure() {}

    public static AutoConfiguredHumboldt configure() {
        return configure(EnvConfig.system(), List.of());
    }

    public static AutoConfiguredHumboldt configure(EnvConfig env) {
        return configure(env, List.of());
    }

    /**
     * Variante avec exporters de spans additionnels — point d'extension pour
     * harness externes (TCK Arquillian) qui doivent injecter dynamiquement
     * un {@link SpanExporter} dans le pipeline trace sans passer par les env
     * vars. Chaque exporter additionnel est attaché via un
     * {@link SimpleSpanProcessor} (export synchrone, requis par les TCK qui
     * font des assertions immédiates après {@code span.end()}).
     */
    public static AutoConfiguredHumboldt configure(EnvConfig env, List<SpanExporter> extraSpanExporters) {
        return configure(env, extraSpanExporters, null);
    }

    public static AutoConfiguredHumboldt configure(EnvConfig env,
                                                    List<SpanExporter> extraSpanExporters,
                                                    Sampler overrideSampler) {
        return configure(env, extraSpanExporters, overrideSampler, null);
    }

    /** Variante avec exporters de métriques additionnels (M4b — bridge OTel SDK). */
    public static AutoConfiguredHumboldt configure(EnvConfig env,
                                                    List<SpanExporter> extraSpanExporters,
                                                    Sampler overrideSampler,
                                                    io.opentelemetry.context.propagation.ContextPropagators overridePropagators,
                                                    List<io.vidocq.humboldt.sdk.metric.export.MetricExporter> extraMetricExporters) {
        EXTRA_METRIC_EXPORTERS.set(extraMetricExporters == null ? List.of() : extraMetricExporters);
        try {
            return configure(env, extraSpanExporters, overrideSampler, overridePropagators);
        } finally {
            EXTRA_METRIC_EXPORTERS.remove();
        }
    }

    /**
     * Slot ThreadLocal pour passer les extra MetricExporters depuis l'overload publique
     * à 5 args jusqu'à la construction du SdkMeterProvider (qui se fait dans la méthode
     * principale {@code configure(env, extras, sampler, propagators)}). Permet d'éviter
     * une duplication du pipeline complet.
     */
    private static final ThreadLocal<List<io.vidocq.humboldt.sdk.metric.export.MetricExporter>>
            EXTRA_METRIC_EXPORTERS = ThreadLocal.withInitial(List::of);

    /**
     * Variante complète avec sampler override + propagators override — pour les
     * harness Arquillian qui chargent un {@code ConfigurableSamplerProvider} et/ou
     * {@code ConfigurablePropagatorProvider} OTel via SPI du WAR.
     *
     * @param overrideSampler     sampler à utiliser pour le tracer provider ; si {@code null},
     *                            fallback sur le parsing standard de {@code OTEL_TRACES_SAMPLER}.
     * @param overridePropagators propagators à utiliser ; si {@code null}, fallback sur
     *                            {@link W3CPropagators#get()} (W3C TraceContext + Baggage).
     */
    public static AutoConfiguredHumboldt configure(EnvConfig env,
                                                    List<SpanExporter> extraSpanExporters,
                                                    Sampler overrideSampler,
                                                    ContextPropagators overridePropagators) {
        Resource resource = buildResource(env);

        // MP Telemetry 2.1 §3.1 : par défaut, le SDK OpenTelemetry est désactivé.
        // L'application doit explicitement set OTEL_SDK_DISABLED=false pour activer
        // l'export. Note : OTel SDK Java natif a le défaut inverse (enabled), mais
        // pour conformité MP Telemetry et stabilité TCK on aligne sur la spec MP.
        boolean sdkDisabled = env.getBoolean("OTEL_SDK_DISABLED", true);
        if (sdkDisabled) {
            LOG.log(Level.INFO,
                    "Humboldt : SDK disabled (OTEL_SDK_DISABLED=true ou non-spécifié, défaut MP Telemetry 2.1)");
            // Providers construits sans aucun SpanProcessor/MetricReader/LogRecordProcessor
            // → l'API OTel reste pleinement utilisable (Span.current(), Tracer.spanBuilder())
            //   mais rien n'est jamais exporté ni accumulé en mémoire.
            return new AutoConfiguredHumboldt(
                    SdkTracerProvider.builder().setResource(resource).build(),
                    SdkMeterProvider.builder().setResource(resource).build(),
                    SdkLoggerProvider.builder().setResource(resource).build(),
                    overridePropagators != null ? overridePropagators : W3CPropagators.get(),
                    null, null, null);
        }

        // --- Tracer ---
        String tracesExporter = env.getOrDefault("OTEL_TRACES_EXPORTER", "otlp");
        Sampler sampler = overrideSampler != null ? overrideSampler : parseSampler(env);
        InMemorySpanExporter inMemSpan = "in-memory".equals(tracesExporter)
                ? InMemorySpanExporter.create() : null;
        SdkTracerProvider.Builder tpBuilder = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(sampler);
        SpanExporter spanExporter = switch (tracesExporter) {
            case "none" -> null;
            case "in-memory" -> inMemSpan;
            case "logging" -> LoggingSpanExporter.create();
            default -> buildOtlpSpanExporter(env);
        };
        if (spanExporter != null) {
            SpanProcessor sp = "in-memory".equals(tracesExporter)
                    ? SimpleSpanProcessor.create(spanExporter)
                    : BatchSpanProcessor.builder(spanExporter).build();
            tpBuilder.addSpanProcessor(sp);
        }
        for (SpanExporter extra : extraSpanExporters) {
            tpBuilder.addSpanProcessor(SimpleSpanProcessor.create(extra));
        }
        SdkTracerProvider tracerProvider = tpBuilder.build();

        // --- Meter ---
        String metricsExporter = env.getOrDefault("OTEL_METRICS_EXPORTER", "otlp");
        InMemoryMetricExporter inMemMetric = "in-memory".equals(metricsExporter)
                ? InMemoryMetricExporter.create() : null;
        SdkMeterProvider.Builder mpBuilder = SdkMeterProvider.builder().setResource(resource);
        MetricExporter metricExporter = switch (metricsExporter) {
            case "none" -> null;
            case "in-memory" -> inMemMetric;
            case "logging" -> io.vidocq.humboldt.sdk.metric.export.LoggingMetricExporter.create();
            default -> buildOtlpMetricExporter(env);
        };
        if (metricExporter != null) {
            // OTEL_METRIC_EXPORT_INTERVAL (en ms) — défaut 60s spec OTel ; 3s côté TCK
            // MP Telemetry pour permettre les awaitility.until() en moins de 15s.
            long intervalMs = env.getLong("OTEL_METRIC_EXPORT_INTERVAL", -1L);
            Duration interval;
            if ("in-memory".equals(metricsExporter)) {
                interval = Duration.ofMinutes(60); // flush() explicite dans les tests
            } else if (intervalMs > 0) {
                interval = Duration.ofMillis(intervalMs);
            } else {
                interval = Duration.ofSeconds(60);
            }
            mpBuilder.registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                    .setInterval(interval)
                    .build());
        }
        // Extra MetricExporters (M4b — bridge OTel SDK via humboldt-tck) passés via le
        // ThreadLocal EXTRA_METRIC_EXPORTERS. Cas TCK : InMemoryMetricExporter du WAR.
        // Interval court pour permettre les awaitility.until() des tests metric (10s timeout).
        for (var extra : EXTRA_METRIC_EXPORTERS.get()) {
            mpBuilder.registerMetricReader(PeriodicMetricReader.builder(extra)
                    .setInterval(Duration.ofMillis(200))
                    .build());
        }
        SdkMeterProvider meterProvider = mpBuilder.build();

        // M4b — JVM metrics OTel SemConv 1.27+ : binde des Observable instruments
        // (memory, cpu, class, thread, gc) sur le Meter humboldt-runtime. Conformité
        // MP Telemetry 2.1 §"Required JVM metrics". Skip si pas d'exporter (none).
        if (metricExporter != null || !EXTRA_METRIC_EXPORTERS.get().isEmpty()) {
            try {
                JvmMetricsBinder.bindAll(meterProvider.get("io.vidocq.humboldt.runtime.jvm"));
            } catch (RuntimeException ignored) {
                // Si MXBean indisponible (env spécifique), on continue sans crasher le boot.
            }
        }

        // --- Logger ---
        String logsExporter = env.getOrDefault("OTEL_LOGS_EXPORTER", "otlp");
        InMemoryLogRecordExporter inMemLog = "in-memory".equals(logsExporter)
                ? InMemoryLogRecordExporter.create() : null;
        SdkLoggerProvider.Builder lpBuilder = SdkLoggerProvider.builder().setResource(resource);
        LogRecordExporter logExporter = switch (logsExporter) {
            case "none" -> null;
            case "in-memory" -> inMemLog;
            case "logging" -> LoggingLogRecordExporter.create();
            default -> buildOtlpLogExporter(env);
        };
        if (logExporter != null) {
            // Simple processor pour in-memory ET logging : la sortie doit être synchrone
            // pour que les tests (TCK JulTest notamment) puissent lire le fichier sans
            // attendre un flush différé.
            boolean useSimple = "in-memory".equals(logsExporter) || "logging".equals(logsExporter);
            LogRecordProcessor lp = useSimple
                    ? SimpleLogRecordProcessor.create(logExporter)
                    : BatchLogRecordProcessor.builder(logExporter).build();
            lpBuilder.addLogRecordProcessor(lp);
        }
        SdkLoggerProvider loggerProvider = lpBuilder.build();

        // Bridge JUL → OTel : auto-installé sur le root logger quand le pipeline logs
        // est en mode production (otlp ou logging). Pas en in-memory pour éviter que
        // les logs internes du runtime polluent les InMemoryLogRecordExporter des tests.
        // Idempotent : ne réinstalle pas si un HumboldtJulHandler est déjà présent.
        boolean installJulBridge = logExporter != null
                && !"in-memory".equals(logsExporter)
                && !"none".equals(logsExporter);
        if (installJulBridge) {
            installJulBridge(loggerProvider);
        }

        ContextPropagators propagators = overridePropagators != null
                ? overridePropagators
                : W3CPropagators.get();

        LOG.log(Level.INFO,
                "Humboldt autoconfig : service.name={0}, traces={1}, metrics={2}, logs={3}, sampler={4}",
                resource.attributes().get(stringKey("service.name")),
                tracesExporter, metricsExporter, logsExporter, sampler.description());

        return new AutoConfiguredHumboldt(
                tracerProvider, meterProvider, loggerProvider, propagators,
                inMemSpan, inMemMetric, inMemLog);
    }

    private static Resource buildResource(EnvConfig env) {
        AttributesBuilder attrs = Attributes.builder();
        attrs.put(stringKey("service.name"), env.getOrDefault("OTEL_SERVICE_NAME", "humboldt"));
        env.get("OTEL_RESOURCE_ATTRIBUTES").ifPresent(raw -> {
            for (String pair : raw.split(",")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String k = pair.substring(0, idx).trim();
                    String v = pair.substring(idx + 1).trim();
                    if (!k.isEmpty()) attrs.put(stringKey(k), v);
                }
            }
        });
        return Resource.create(attrs.build());
    }

    /**
     * Installe le {@link HumboldtJulHandler} sur le root JUL logger pour piper les
     * {@code java.util.logging} vers le pipeline OTel humboldt. Idempotent : si un
     * {@link HumboldtJulHandler} est déjà présent, ne rien faire.
     */
    private static void installJulBridge(SdkLoggerProvider loggerProvider) {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        for (java.util.logging.Handler existing : root.getHandlers()) {
            if (existing instanceof HumboldtJulHandler) return;
        }
        HumboldtJulHandler bridge = new HumboldtJulHandler(loggerProvider);
        bridge.setLevel(java.util.logging.Level.ALL);
        root.addHandler(bridge);
    }

    private static Sampler parseSampler(EnvConfig env) {
        String name = env.getOrDefault("OTEL_TRACES_SAMPLER", "parentbased_always_on");
        return switch (name) {
            case "always_on" -> Sampler.alwaysOn();
            case "always_off" -> Sampler.alwaysOff();
            case "traceidratio" -> Sampler.traceIdRatioBased(env.getDouble("OTEL_TRACES_SAMPLER_ARG", 1.0));
            case "parentbased_always_off" -> Sampler.parentBased(Sampler.alwaysOff());
            case "parentbased_traceidratio" -> Sampler.parentBased(
                    Sampler.traceIdRatioBased(env.getDouble("OTEL_TRACES_SAMPLER_ARG", 1.0)));
            default -> Sampler.parentBased(Sampler.alwaysOn());
        };
    }

    private static OtlpHttpSpanExporter buildOtlpSpanExporter(EnvConfig env) {
        String endpoint = env.getOrDefault("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                env.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", DEFAULT_ENDPOINT) + "/v1/traces");
        OtlpHttpSpanExporter.Builder b = OtlpHttpSpanExporter.builder().setEndpoint(endpoint);
        applyHeaders(env, b::addHeader);
        return b.build();
    }

    private static OtlpHttpMetricExporter buildOtlpMetricExporter(EnvConfig env) {
        String endpoint = env.getOrDefault("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
                env.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", DEFAULT_ENDPOINT) + "/v1/metrics");
        OtlpHttpMetricExporter.Builder b = OtlpHttpMetricExporter.builder().setEndpoint(endpoint);
        applyHeaders(env, b::addHeader);
        return b.build();
    }

    private static OtlpHttpLogExporter buildOtlpLogExporter(EnvConfig env) {
        String endpoint = env.getOrDefault("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT",
                env.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", DEFAULT_ENDPOINT) + "/v1/logs");
        OtlpHttpLogExporter.Builder b = OtlpHttpLogExporter.builder().setEndpoint(endpoint);
        applyHeaders(env, b::addHeader);
        return b.build();
    }

    private static void applyHeaders(EnvConfig env, java.util.function.BiConsumer<String, String> setter) {
        env.get("OTEL_EXPORTER_OTLP_HEADERS").ifPresent(raw -> {
            Arrays.stream(raw.split(",")).forEach(pair -> {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    setter.accept(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                }
            });
        });
    }
}
