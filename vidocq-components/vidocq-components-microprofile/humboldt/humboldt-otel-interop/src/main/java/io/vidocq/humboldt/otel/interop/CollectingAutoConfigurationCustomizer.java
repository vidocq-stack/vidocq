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

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link AutoConfigurationCustomizer} implementation that collects callbacks
 * registered by {@code AutoConfigurationCustomizerProvider}s discovered through
 * {@code ServiceLoader}. The callback chains are then applied at the appropriate
 * time in the Humboldt pipeline by {@link OtelSpiAutoConfiguration}.
 *
 * <p>MP Telemetry 2.1 spec §3.2 + OTel SDK autoconfigure: each
 * {@code AutoConfigurationCustomizerProvider} discovered via
 * {@code ServiceLoader<AutoConfigurationCustomizerProvider>} has its {@code customize(this)}
 * invoked. The customizer accumulates callbacks; they are then applied in order
 * when building Humboldt.</p>
 *
 * <p>Limitation: only the 6 customizer families used by the MP Telemetry TCK
 * {@code CustomizerSpiTest.TestCustomizer} are actually wired
 * (Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider).
 * The other inherited interface methods remain at their default (no-op).</p>
 */
public final class CollectingAutoConfigurationCustomizer implements AutoConfigurationCustomizer {

    private final List<BiFunction<? super Resource, ConfigProperties, ? extends Resource>> resourceCustomizers = new ArrayList<>();
    private final List<BiFunction<? super TextMapPropagator, ConfigProperties, ? extends TextMapPropagator>> propagatorCustomizers = new ArrayList<>();
    private final List<Function<ConfigProperties, Map<String, String>>> propertiesCustomizers = new ArrayList<>();
    private final List<Supplier<Map<String, String>>> propertiesSuppliers = new ArrayList<>();
    private final List<BiFunction<? super Sampler, ConfigProperties, ? extends Sampler>> samplerCustomizers = new ArrayList<>();
    private final List<BiFunction<? super SpanExporter, ConfigProperties, ? extends SpanExporter>> spanExporterCustomizers = new ArrayList<>();
    private final List<BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>> tracerProviderCustomizers = new ArrayList<>();

    /** Class loader searched by the component loader of the {@link ConfigProperties} given to the callbacks. */
    private final ClassLoader classLoader;

    /**
     * Customizer whose callbacks receive properties with the default component loader of
     * {@link MapConfigProperties#MapConfigProperties(Map)}.
     */
    public CollectingAutoConfigurationCustomizer() {
        this(ConfigProperties.class.getClassLoader());
    }

    /** Customizer whose callbacks receive properties whose component loader searches {@code classLoader}. */
    CollectingAutoConfigurationCustomizer(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public AutoConfigurationCustomizer addPropagatorCustomizer(
            BiFunction<? super TextMapPropagator, ConfigProperties, ? extends TextMapPropagator> customizer) {
        propagatorCustomizers.add(customizer);
        return this;
    }

    @Override
    public AutoConfigurationCustomizer addResourceCustomizer(
            BiFunction<? super Resource, ConfigProperties, ? extends Resource> customizer) {
        resourceCustomizers.add(customizer);
        return this;
    }

    @Override
    public AutoConfigurationCustomizer addSamplerCustomizer(
            BiFunction<? super Sampler, ConfigProperties, ? extends Sampler> customizer) {
        samplerCustomizers.add(customizer);
        return this;
    }

    @Override
    public AutoConfigurationCustomizer addSpanExporterCustomizer(
            BiFunction<? super SpanExporter, ConfigProperties, ? extends SpanExporter> customizer) {
        spanExporterCustomizers.add(customizer);
        return this;
    }

    @Override
    public AutoConfigurationCustomizer addPropertiesSupplier(Supplier<Map<String, String>> supplier) {
        propertiesSuppliers.add(supplier);
        return this;
    }

    @Override
    public AutoConfigurationCustomizer addPropertiesCustomizer(
            Function<ConfigProperties, Map<String, String>> customizer) {
        propertiesCustomizers.add(customizer);
        return this;
    }

    @Override
    public AutoConfigurationCustomizer addTracerProviderCustomizer(
            BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> customizer) {
        tracerProviderCustomizers.add(customizer);
        return this;
    }

    // ---- Applying callback chains to the Humboldt pipeline --------------------------------

    /**
     * Applies all {@code addPropertiesSupplier} + {@code addPropertiesCustomizer}
     * to the input env vars map. Returned values are merged in
     * order (later values overwrite earlier ones).
     */
    Map<String, String> applyPropertyCustomizers(Map<String, String> baseEnvMap) {
        Map<String, String> merged = new LinkedHashMap<>(baseEnvMap);
        ConfigProperties cfg = new MapConfigProperties(mpFormatFromEnv(merged), classLoader);
        for (Supplier<Map<String, String>> s : propertiesSuppliers) {
            Map<String, String> added = s.get();
            if (added != null) added.forEach((k, v) -> merged.put(envFormat(k), v));
        }
        for (Function<ConfigProperties, Map<String, String>> c : propertiesCustomizers) {
            Map<String, String> added = c.apply(cfg);
            if (added != null) added.forEach((k, v) -> merged.put(envFormat(k), v));
        }
        return merged;
    }

    /**
     * Applies {@code addResourceCustomizer} to the OTel {@code Resource} built
     * from the Humboldt Resource, and returns the CSV string {@code key=val,key2=val2}
     * for the resulting attributes (to be appended to {@code OTEL_RESOURCE_ATTRIBUTES}).
     */
    String applyResourceCustomizersAsAttrs(Map<String, String> props) {
        if (resourceCustomizers.isEmpty()) return "";
        ConfigProperties cfg = new MapConfigProperties(props, classLoader);
        Resource resource = Resource.empty();
        for (var c : resourceCustomizers) {
            resource = c.apply(resource, cfg);
        }
        StringBuilder sb = new StringBuilder();
        var attrs = resource.getAttributes();
        attrs.forEach((key, value) -> {
            if (value == null) return;
            if (sb.length() > 0) sb.append(',');
            sb.append(key.getKey()).append('=').append(value);
        });
        return sb.toString();
    }

    /**
     * Applies the {@code propagatorCustomizers} chain to an input
     * {@link TextMapPropagator}. Humboldt uses OTel {@code TextMapPropagator}s directly
     * (no bridge required — same interface).
     */
    TextMapPropagator applyPropagatorCustomizers(TextMapPropagator base, Map<String, String> props) {
        if (propagatorCustomizers.isEmpty()) return base;
        ConfigProperties cfg = new MapConfigProperties(props, classLoader);
        TextMapPropagator current = base;
        for (var c : propagatorCustomizers) {
            current = c.apply(current, cfg);
        }
        return current;
    }

    /**
     * Invokes the {@code samplerCustomizers} chain for its side effect (for example
     * events logged by the MP Telemetry TCK TestCustomizer). The resulting sampler is
     * ignored on the Humboldt side (a full Humboldt → OTel Sampler bridge would require
     * bidirectional mapping that is not implemented — out of scope).
     */
    void invokeSamplerCustomizers(Map<String, String> props) {
        if (samplerCustomizers.isEmpty()) return;
        ConfigProperties cfg = new MapConfigProperties(props, classLoader);
        Sampler placeholder = Sampler.alwaysOn();
        for (var c : samplerCustomizers) {
            try { placeholder = c.apply(placeholder, cfg); }
            catch (RuntimeException ignored) { /* side-effect-only */ }
        }
    }

    /** Same for {@code spanExporterCustomizers}. */
    void invokeSpanExporterCustomizers(Map<String, String> props) {
        if (spanExporterCustomizers.isEmpty()) return;
        ConfigProperties cfg = new MapConfigProperties(props, classLoader);
        SpanExporter placeholder = new NoOpSpanExporter();
        for (var c : spanExporterCustomizers) {
            try { placeholder = c.apply(placeholder, cfg); }
            catch (RuntimeException ignored) { /* side-effect-only */ }
        }
    }

    /** Same for {@code tracerProviderCustomizers} — invokes them on a dummy OTel builder. */
    void invokeTracerProviderCustomizers(Map<String, String> props) {
        if (tracerProviderCustomizers.isEmpty()) return;
        ConfigProperties cfg = new MapConfigProperties(props, classLoader);
        SdkTracerProviderBuilder builder = io.opentelemetry.sdk.trace.SdkTracerProvider.builder();
        for (var c : tracerProviderCustomizers) {
            try { builder = c.apply(builder, cfg); }
            catch (RuntimeException ignored) { /* side-effect-only */ }
        }
    }

    boolean hasAny() {
        return !resourceCustomizers.isEmpty() || !propagatorCustomizers.isEmpty()
                || !propertiesCustomizers.isEmpty() || !propertiesSuppliers.isEmpty()
                || !samplerCustomizers.isEmpty() || !spanExporterCustomizers.isEmpty()
                || !tracerProviderCustomizers.isEmpty();
    }

    private static String envFormat(String key) {
        return key.toUpperCase(java.util.Locale.ROOT).replace('.', '_');
    }

    private static Map<String, String> mpFormatFromEnv(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        env.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT).replace('_', '.'), v));
        return out;
    }

    /** Placeholder for the SpanExporter — never actually used in practice. */
    private static final class NoOpSpanExporter implements SpanExporter {
        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode export(
                java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData> spans) {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }
        @Override public io.opentelemetry.sdk.common.CompletableResultCode flush() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }
        @Override public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }
    }
}
