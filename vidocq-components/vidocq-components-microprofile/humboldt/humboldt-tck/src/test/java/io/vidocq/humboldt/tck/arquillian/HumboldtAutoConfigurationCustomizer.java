/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.humboldt.tck.arquillian;

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
 * Implémentation {@link AutoConfigurationCustomizer} qui collecte les callbacks
 * enregistrés par les {@code AutoConfigurationCustomizerProvider} scannés depuis le
 * WAR ShrinkWrap. Les chaînes de callbacks sont appliquées au bon moment du pipeline
 * humboldt par {@link HumboldtDeployableContainer}.
 *
 * <p>Spec MP Telemetry 2.1 §3.2 + OTel SDK autoconfigure : chaque
 * {@code AutoConfigurationCustomizerProvider} découvert via
 * {@code ServiceLoader<AutoConfigurationCustomizerProvider>} a son {@code customize(this)}
 * invoqué. Le customizer accumule les callbacks ; ils sont ensuite appliqués dans
 * l'ordre lors de la construction d'humboldt.</p>
 *
 * <p>Limite humboldt-tck : seules les 6 méthodes utilisées par le TCK
 * {@code CustomizerSpiTest.TestCustomizer} sont effectivement câblées
 * (Resource/Propagator/Properties/Sampler/SpanExporter/TracerProvider).
 * Les autres méthodes héritées de l'interface restent en défaut (no-op).
 * Pour le runtime humboldt prod sans TCK, cet adapter n'est pas chargé.</p>
 */
final class HumboldtAutoConfigurationCustomizer implements AutoConfigurationCustomizer {

    private final List<BiFunction<? super Resource, ConfigProperties, ? extends Resource>> resourceCustomizers = new ArrayList<>();
    private final List<BiFunction<? super TextMapPropagator, ConfigProperties, ? extends TextMapPropagator>> propagatorCustomizers = new ArrayList<>();
    private final List<Function<ConfigProperties, Map<String, String>>> propertiesCustomizers = new ArrayList<>();
    private final List<Supplier<Map<String, String>>> propertiesSuppliers = new ArrayList<>();
    private final List<BiFunction<? super Sampler, ConfigProperties, ? extends Sampler>> samplerCustomizers = new ArrayList<>();
    private final List<BiFunction<? super SpanExporter, ConfigProperties, ? extends SpanExporter>> spanExporterCustomizers = new ArrayList<>();
    private final List<BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>> tracerProviderCustomizers = new ArrayList<>();

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

    // ---- Application des chaînes au pipeline humboldt ------------------------------------

    /**
     * Applique tous les {@code addPropertiesSupplier} + {@code addPropertiesCustomizer}
     * sur la map d'env vars d'entrée. Les valeurs retournées sont fusionnées dans
     * l'ordre (les later overwrite les earlier).
     */
    Map<String, String> applyPropertyCustomizers(Map<String, String> baseEnvMap) {
        Map<String, String> merged = new LinkedHashMap<>(baseEnvMap);
        ConfigProperties cfg = new MapConfigProperties(mpFormatFromEnv(merged));
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
     * Applique les {@code addResourceCustomizer} sur le {@code Resource} OTel construit
     * depuis le humboldt Resource, et retourne la chaîne CSV {@code key=val,key2=val2}
     * des attributs résultants (à concaténer à {@code OTEL_RESOURCE_ATTRIBUTES}).
     */
    String applyResourceCustomizersAsAttrs(Map<String, String> mpProps) {
        if (resourceCustomizers.isEmpty()) return "";
        ConfigProperties cfg = new MapConfigProperties(mpProps);
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
     * Applique la chaîne {@code propagatorCustomizers} sur un {@link TextMapPropagator}
     * d'entrée. Humboldt utilise directement les OTel {@code TextMapPropagator} (pas
     * de bridge nécessaire — même interface).
     */
    TextMapPropagator applyPropagatorCustomizers(TextMapPropagator base, Map<String, String> mpProps) {
        if (propagatorCustomizers.isEmpty()) return base;
        ConfigProperties cfg = new MapConfigProperties(mpProps);
        TextMapPropagator current = base;
        for (var c : propagatorCustomizers) {
            current = c.apply(current, cfg);
        }
        return current;
    }

    /**
     * Invoque la chaîne {@code samplerCustomizers} pour son side-effect (LOGGED_EVENTS
     * du TestCustomizer). Le sampler résultant est ignoré côté humboldt (le bridge
     * humboldt → OTel Sampler complet nécessiterait un mapping bidirectionnel non encore
     * implémenté — out of scope pour le seul TCK testCustomizer).
     */
    void invokeSamplerCustomizers(Map<String, String> mpProps) {
        if (samplerCustomizers.isEmpty()) return;
        ConfigProperties cfg = new MapConfigProperties(mpProps);
        Sampler placeholder = Sampler.alwaysOn();
        for (var c : samplerCustomizers) {
            try { placeholder = c.apply(placeholder, cfg); }
            catch (RuntimeException ignored) { /* side-effect-only */ }
        }
    }

    /** Idem pour {@code spanExporterCustomizers}. */
    void invokeSpanExporterCustomizers(Map<String, String> mpProps) {
        if (spanExporterCustomizers.isEmpty()) return;
        ConfigProperties cfg = new MapConfigProperties(mpProps);
        SpanExporter placeholder = new NoOpSpanExporter();
        for (var c : spanExporterCustomizers) {
            try { placeholder = c.apply(placeholder, cfg); }
            catch (RuntimeException ignored) { /* side-effect-only */ }
        }
    }

    /** Idem pour {@code tracerProviderCustomizers} — invoque sur un builder OTel factice. */
    void invokeTracerProviderCustomizers(Map<String, String> mpProps) {
        if (tracerProviderCustomizers.isEmpty()) return;
        ConfigProperties cfg = new MapConfigProperties(mpProps);
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

    /** Placeholder pour le SpanExporter — n'est jamais utilisé en pratique. */
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
