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
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;
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
        return configure(EnvConfig.system());
    }

    public static AutoConfiguredHumboldt configure(EnvConfig env) {
        Resource resource = buildResource(env);

        // --- Tracer ---
        String tracesExporter = env.getOrDefault("OTEL_TRACES_EXPORTER", "otlp");
        Sampler sampler = parseSampler(env);
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
        SdkTracerProvider tracerProvider = tpBuilder.build();

        // --- Meter ---
        String metricsExporter = env.getOrDefault("OTEL_METRICS_EXPORTER", "otlp");
        InMemoryMetricExporter inMemMetric = "in-memory".equals(metricsExporter)
                ? InMemoryMetricExporter.create() : null;
        SdkMeterProvider.Builder mpBuilder = SdkMeterProvider.builder().setResource(resource);
        MetricExporter metricExporter = switch (metricsExporter) {
            case "none" -> null;
            case "in-memory" -> inMemMetric;
            default -> buildOtlpMetricExporter(env);
        };
        if (metricExporter != null) {
            mpBuilder.registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                    .setInterval("in-memory".equals(metricsExporter)
                            ? Duration.ofMinutes(60)  // flush() explicite dans les tests
                            : Duration.ofSeconds(60))
                    .build());
        }
        SdkMeterProvider meterProvider = mpBuilder.build();

        // --- Logger ---
        String logsExporter = env.getOrDefault("OTEL_LOGS_EXPORTER", "otlp");
        InMemoryLogRecordExporter inMemLog = "in-memory".equals(logsExporter)
                ? InMemoryLogRecordExporter.create() : null;
        SdkLoggerProvider.Builder lpBuilder = SdkLoggerProvider.builder().setResource(resource);
        LogRecordExporter logExporter = switch (logsExporter) {
            case "none" -> null;
            case "in-memory" -> inMemLog;
            default -> buildOtlpLogExporter(env);
        };
        if (logExporter != null) {
            LogRecordProcessor lp = "in-memory".equals(logsExporter)
                    ? SimpleLogRecordProcessor.create(logExporter)
                    : BatchLogRecordProcessor.builder(logExporter).build();
            lpBuilder.addLogRecordProcessor(lp);
        }
        SdkLoggerProvider loggerProvider = lpBuilder.build();

        ContextPropagators propagators = W3CPropagators.get();

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
