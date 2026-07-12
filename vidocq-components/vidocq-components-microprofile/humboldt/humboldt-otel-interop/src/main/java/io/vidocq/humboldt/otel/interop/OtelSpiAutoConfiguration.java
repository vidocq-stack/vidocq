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
package io.vidocq.humboldt.otel.interop;

import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.metric.export.MetricExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Discovers OpenTelemetry SDK autoconfigure SPI providers on a caller-supplied
 * {@link ClassLoader} and adapts them to the Humboldt SDK.
 *
 * <p>The OTel SDK ships an autoconfigure extension whose SPI
 * ({@code io.opentelemetry.sdk.autoconfigure.spi.*}) is the standard way for
 * applications and test harnesses (notably the MicroProfile Telemetry TCK) to
 * plug in custom span/metric exporters, samplers, propagators, resources and
 * customizers. Humboldt has its own SDK, so those providers cannot be consumed
 * directly: this entry point discovers them via {@link ServiceLoader} and
 * bridges each one to its Humboldt counterpart
 * ({@link OtelSpanExporterBridge}, {@link OtelMetricExporterBridge},
 * {@link OtelSamplerBridge}, ...).</p>
 *
 * <p>The resulting {@link Result} is meant to be fed into
 * {@code HumboldtAutoConfigure.configure(EnvConfig, extraSpanExporters,
 * samplerOverride, propagatorsOverride, extraMetricExporters)}.</p>
 *
 * <p>The discovery and environment-adjustment logic is a faithful port of the
 * Arquillian {@code HumboldtDeployableContainer} harness that passes the
 * MP Telemetry 2.1 TCK 85/85, with archive scanning replaced by
 * {@code ServiceLoader.load(spi, loader)}.</p>
 */
public final class OtelSpiAutoConfiguration {

    private static final Logger LOG = System.getLogger(OtelSpiAutoConfiguration.class.getName());

    private OtelSpiAutoConfiguration() {}

    /**
     * Outcome of the SPI discovery.
     *
     * @param env                 adjusted OTel environment (property customizers applied,
     *                            {@code OTEL_RESOURCE_ATTRIBUTES} merged with SPI resources,
     *                            {@code OTEL_TRACES_EXPORTER}/{@code OTEL_METRICS_EXPORTER}/
     *                            {@code OTEL_LOGS_EXPORTER} adjusted)
     * @param extraSpanExporters  OTel SPI span exporters bridged to Humboldt
     * @param samplerOverride     bridged SPI sampler, or {@code null} if none matches
     * @param propagatorsOverride SPI/customized propagators, or {@code null} for the default
     * @param extraMetricExporters OTel SPI metric exporters bridged to Humboldt
     */
    public record Result(
            Map<String, String> env,
            List<SpanExporter> extraSpanExporters,
            Sampler samplerOverride,
            ContextPropagators propagatorsOverride,
            List<MetricExporter> extraMetricExporters) {}

    /**
     * Runs the OTel autoconfigure SPI discovery against {@code loader} and computes
     * the Humboldt configuration overrides.
     *
     * @param otelEnv OTel environment ({@code SCREAMING_SNAKE_CASE} keys, e.g.
     *                {@code OTEL_TRACES_EXPORTER}); never mutated
     * @param loader  the ClassLoader whose {@code META-INF/services} entries are
     *                scanned (at runtime, typically the deployment's TCCL)
     * @return the discovery {@link Result} to pass to Humboldt autoconfiguration
     */
    public static Result discover(Map<String, String> otelEnv, ClassLoader loader) {
        // The SPI providers historically received the MP Config form of the keys
        // (lower.dot.case, e.g. otel.traces.exporter) — derive it from the env form.
        Map<String, String> props = toDottedProps(otelEnv);

        // Span exporter providers: name → provider map keyed by getName().
        // If otel.traces.exporter designates a discovered provider (e.g. "in-memory"),
        // create its OTel SpanExporter and bridge it to Humboldt — this is the
        // mechanism the TCK uses to retrieve its InMemorySpanExporter.
        Map<String, ConfigurableSpanExporterProvider> spanExporterProviders = new LinkedHashMap<>();
        forEachProvider(ConfigurableSpanExporterProvider.class, loader, p -> {
            spanExporterProviders.put(p.getName(), p);
            LOG.log(Level.INFO, "  -> SpanExporterProvider discovered: {0} (name={1})",
                    p.getClass().getName(), p.getName());
        });
        List<SpanExporter> extraSpanExporters = new ArrayList<>();
        String tracesExporterName = props.get("otel.traces.exporter");
        if (tracesExporterName != null && spanExporterProviders.containsKey(tracesExporterName)) {
            ConfigurableSpanExporterProvider p = spanExporterProviders.get(tracesExporterName);
            var otelExporter = p.createExporter(new MapConfigProperties(props));
            extraSpanExporters.add(new OtelSpanExporterBridge(otelExporter));
            LOG.log(Level.INFO, "  -> OTel SpanExporter '{0}' bridged to Humboldt", tracesExporterName);
        }

        // ResourceProvider SPI: invoke createResource(configProperties) on each provider
        // and append the resulting attributes to OTEL_RESOURCE_ATTRIBUTES. Humboldt's
        // Resource picks them up through its standard CSV key=value parsing.
        String spiResourceAttrs = loadResourceProviderAttrs(loader, props);

        // ConfigurableSamplerProvider SPI: if otel.traces.sampler matches a provider
        // getName(), create the OTel Sampler and wrap it in an OtelSamplerBridge.
        Sampler spiSamplerOverride = resolveSpiSampler(loader, props);

        // ConfigurablePropagatorProvider SPI: if otel.propagators (or the MP Telemetry
        // property) lists names matching discovered providers or builtins, compose them.
        ContextPropagators spiPropagators = resolveSpiPropagators(loader, props);

        // AutoConfigurationCustomizerProvider SPI: collect the callback chains
        // (Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider).
        CollectingAutoConfigurationCustomizer autoCustomizer = scanAutoConfigCustomizers(loader);

        // ConfigurableMetricExporterProvider SPI: symmetric to the span exporter path.
        List<MetricExporter> extraMetricExporters = loadMetricExporters(loader, props);

        // Adjust the OTel env. If an external bridge is in place, force
        // OTEL_TRACES_EXPORTER=none / OTEL_METRICS_EXPORTER=none to avoid Humboldt
        // adding its own native in-memory exporter next to the bridged one.
        Map<String, String> envMap = new LinkedHashMap<>(otelEnv);
        // Apply PropertiesCustomizer / PropertiesSupplier BEFORE merging other
        // modifications — their values are "defaults" that can be overridden by
        // the other sources.
        envMap = autoCustomizer.applyPropertyCustomizers(envMap);
        if (!spiResourceAttrs.isEmpty()) {
            String existing = envMap.get("OTEL_RESOURCE_ATTRIBUTES");
            envMap.put("OTEL_RESOURCE_ATTRIBUTES",
                    existing == null || existing.isEmpty() ? spiResourceAttrs : existing + "," + spiResourceAttrs);
        }
        // ResourceCustomizer — invoke the chain and merge the resulting attrs into
        // OTEL_RESOURCE_ATTRIBUTES (Humboldt re-parses them when building its Resource).
        String customizerResourceAttrs = autoCustomizer.applyResourceCustomizersAsAttrs(props);
        if (!customizerResourceAttrs.isEmpty()) {
            String existing = envMap.get("OTEL_RESOURCE_ATTRIBUTES");
            envMap.put("OTEL_RESOURCE_ATTRIBUTES",
                    existing == null || existing.isEmpty() ? customizerResourceAttrs
                            : existing + "," + customizerResourceAttrs);
        }
        // Side-effect-only invocations (the result of Sampler/SpanExporter/
        // TracerProvider customizers cannot be bridged to Humboldt 1:1 without full
        // bidirectional bridges — out of scope; SPI consumers such as the TCK
        // CustomizerSpiTest only assert on the callbacks' logged side effects).
        autoCustomizer.invokeSamplerCustomizers(props);
        autoCustomizer.invokeSpanExporterCustomizers(props);
        autoCustomizer.invokeTracerProviderCustomizers(props);
        envMap.putIfAbsent("OTEL_TRACES_SAMPLER", "always_on");
        if (!extraMetricExporters.isEmpty()) {
            envMap.put("OTEL_METRICS_EXPORTER", "none");
        } else {
            envMap.putIfAbsent("OTEL_METRICS_EXPORTER", "none");
        }
        envMap.putIfAbsent("OTEL_LOGS_EXPORTER", "none");
        if (!extraSpanExporters.isEmpty()) {
            envMap.put("OTEL_TRACES_EXPORTER", "none");
        } else {
            envMap.putIfAbsent("OTEL_TRACES_EXPORTER", "none");
        }

        // PropagatorCustomizer — apply the chain to the final propagator (spiPropagators
        // if defined, otherwise the W3C default). Wrap the result in a new
        // ContextPropagators if the chain transformed it.
        if (autoCustomizer.hasAny()) {
            TextMapPropagator basePropagator = spiPropagators != null
                    ? spiPropagators.getTextMapPropagator()
                    : W3CPropagators.textMap();
            TextMapPropagator customized = autoCustomizer.applyPropagatorCustomizers(basePropagator, props);
            if (customized != basePropagator) {
                spiPropagators = ContextPropagators.create(customized);
            }
        }

        return new Result(envMap, extraSpanExporters, spiSamplerOverride, spiPropagators, extraMetricExporters);
    }

    /**
     * Scans {@link ResourceProvider}s, invokes {@code createResource(configProperties)}
     * on each provider, and returns the attributes as a CSV string
     * {@code key1=val1,key2=val2} ready to be appended to {@code OTEL_RESOURCE_ATTRIBUTES}.
     */
    private static String loadResourceProviderAttrs(ClassLoader loader, Map<String, String> props) {
        StringBuilder attrs = new StringBuilder();
        MapConfigProperties configProps = new MapConfigProperties(props);
        forEachProvider(ResourceProvider.class, loader, provider -> {
            var otelResource = provider.createResource(configProps);
            if (otelResource == null) return;
            otelResource.getAttributes().forEach((key, value) -> {
                if (value == null) return;
                if (attrs.length() > 0) attrs.append(',');
                attrs.append(key.getKey()).append('=').append(value);
            });
            LOG.log(Level.INFO, "  -> ResourceProvider discovered: {0} (attrs={1})",
                    provider.getClass().getName(), otelResource.getAttributes());
        });
        return attrs.toString();
    }

    /**
     * Scans {@link ConfigurableSamplerProvider}s. If {@code otel.traces.sampler}
     * matches a provider's {@code getName()}, instantiates the OTel sampler through
     * {@code createSampler(configProperties)} and wraps it in an {@link OtelSamplerBridge}.
     *
     * @return a Humboldt {@code Sampler} ready to be passed to
     *         {@code HumboldtAutoConfigure.configure(...)}, or {@code null} if no
     *         provider matches.
     */
    private static Sampler resolveSpiSampler(ClassLoader loader, Map<String, String> props) {
        String configuredName = props.get("otel.traces.sampler");
        if (configuredName == null) return null;

        MapConfigProperties configProps = new MapConfigProperties(props);
        List<Sampler> match = new ArrayList<>(1);
        forEachProvider(ConfigurableSamplerProvider.class, loader, provider -> {
            if (!match.isEmpty() || !configuredName.equals(provider.getName())) return;
            var otelSampler = provider.createSampler(configProps);
            if (otelSampler != null) {
                LOG.log(Level.INFO, "  -> SamplerProvider '{0}' ({1}) bridged via OtelSamplerBridge",
                        configuredName, provider.getClass().getName());
                match.add(new OtelSamplerBridge(otelSampler));
            }
        });
        return match.isEmpty() ? null : match.get(0);
    }

    /**
     * Scans {@link ConfigurablePropagatorProvider}s. If {@code otel.propagators}
     * (or the MP Telemetry equivalent) contains names matching a discovered provider's
     * {@code getName()} or a builtin (tracecontext, baggage, b3, b3multi, jaeger),
     * composes a {@link ContextPropagators} from them.
     *
     * @return a composite {@code ContextPropagators}, or {@code null} if no custom
     *         propagator configuration is required (the caller then keeps the W3C default).
     */
    private static ContextPropagators resolveSpiPropagators(ClassLoader loader, Map<String, String> props) {
        // MP Telemetry §3.3: the mp_telemetry.propagators property is also accepted,
        // mapped to otel.propagators (dotted form after env-key normalization).
        String configured = props.getOrDefault("otel.propagators",
                props.get("mp.telemetry.propagators"));
        if (configured == null) return null;

        // Split the list of names (CSV, spaces tolerated)
        List<String> names = new ArrayList<>();
        for (String name : configured.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        if (names.isEmpty()) return null;

        // Discover custom propagator providers (such as the TCK's TestPropagator).
        // They may be absent if only builtins are used — the switch below handles that.
        MapConfigProperties configProps = new MapConfigProperties(props);
        Map<String, TextMapPropagator> byName = new LinkedHashMap<>();
        forEachProvider(ConfigurablePropagatorProvider.class, loader, provider -> {
            var propagator = provider.getPropagator(configProps);
            if (propagator != null) {
                byName.put(provider.getName(), propagator);
                LOG.log(Level.INFO, "  -> PropagatorProvider discovered: {0} (name={1})",
                        provider.getClass().getName(), provider.getName());
            }
        });

        // Compose the final list: for each name in `otel.propagators`, use the
        // builtin if recognized, otherwise the custom SPI provider.
        List<TextMapPropagator> chosen = new ArrayList<>();
        for (String n : names) {
            switch (n) {
                case "tracecontext" -> chosen.add(io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance());
                case "baggage" -> chosen.add(io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance());
                case "b3" -> chosen.add(io.opentelemetry.extension.trace.propagation.B3Propagator.injectingSingleHeader());
                case "b3multi" -> chosen.add(io.opentelemetry.extension.trace.propagation.B3Propagator.injectingMultiHeaders());
                case "jaeger" -> chosen.add(io.opentelemetry.extension.trace.propagation.JaegerPropagator.getInstance());
                default -> {
                    var p = byName.get(n);
                    if (p != null) chosen.add(p);
                    else LOG.log(Level.WARNING,
                            "  Propagator '{0}' requested but unavailable (neither builtin nor discovered SPI)", n);
                }
            }
        }
        if (chosen.isEmpty()) return null;
        return ContextPropagators.create(TextMapPropagator.composite(chosen));
    }

    /**
     * Scans {@link ConfigurableMetricExporterProvider}s. If {@code otel.metrics.exporter}
     * matches a discovered provider's {@code getName()}, instantiates the OTel
     * MetricExporter through {@code createExporter()} and wraps it in
     * {@link OtelMetricExporterBridge} for integration into the Humboldt pipeline.
     */
    private static List<MetricExporter> loadMetricExporters(ClassLoader loader, Map<String, String> props) {
        String configured = props.get("otel.metrics.exporter");
        if (configured == null) return List.of();

        List<MetricExporter> out = new ArrayList<>();
        MapConfigProperties cfg = new MapConfigProperties(props);
        forEachProvider(ConfigurableMetricExporterProvider.class, loader, provider -> {
            if (!configured.equals(provider.getName())) return;
            var otelExporter = provider.createExporter(cfg);
            if (otelExporter != null) {
                out.add(new OtelMetricExporterBridge(otelExporter));
                LOG.log(Level.INFO, "  -> MetricExporter '{0}' ({1}) bridged to Humboldt",
                        configured, provider.getClass().getName());
            }
        });
        return out;
    }

    /**
     * Scans {@link AutoConfigurationCustomizerProvider}s and invokes
     * {@code customize(collector)} on each provider to collect the callback chains
     * (Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider).
     */
    private static CollectingAutoConfigurationCustomizer scanAutoConfigCustomizers(ClassLoader loader) {
        CollectingAutoConfigurationCustomizer customizer = new CollectingAutoConfigurationCustomizer();
        forEachProvider(AutoConfigurationCustomizerProvider.class, loader, provider -> {
            provider.customize(customizer);
            LOG.log(Level.INFO, "  -> AutoConfigurationCustomizerProvider discovered: {0}",
                    provider.getClass().getName());
        });
        return customizer;
    }

    /**
     * Iterates {@code ServiceLoader.load(spi, loader)}, tolerating individual
     * provider failures (mirrors the per-line tolerance of the original
     * archive-scanning harness: a broken provider is logged and skipped).
     */
    private static <S> void forEachProvider(Class<S> spi, ClassLoader loader, Consumer<S> action) {
        Iterator<S> it = ServiceLoader.load(spi, loader).iterator();
        while (true) {
            boolean hasNext;
            try {
                hasNext = it.hasNext();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "  {0} discovery aborted: {1}", spi.getSimpleName(), t.toString());
                return;
            }
            if (!hasNext) return;
            try {
                action.accept(it.next());
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "  Provider ignored ({0}): {1}", spi.getSimpleName(), t.toString());
            }
        }
    }

    /**
     * Derives the {@code lower.dot.case} property view from the OTel env form:
     * {@code OTEL_TRACES_EXPORTER} → {@code otel.traces.exporter}. This is the key
     * form SPI providers receive through {@link MapConfigProperties}.
     */
    private static Map<String, String> toDottedProps(Map<String, String> otelEnv) {
        Map<String, String> out = new LinkedHashMap<>();
        otelEnv.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT).replace('_', '.'), v));
        return out;
    }
}
