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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.vidocq.humboldt.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers each OpenTelemetry autoconfigure SPI handled by
 * {@link OtelSpiAutoConfiguration#discover(Map, ClassLoader)}: samplers, propagators, metric
 * exporters, resources and customizers, plus the tolerance to broken providers.
 */
class OtelSpiProvidersDiscoveryTest {

    private static final String TRACES_SAMPLER = "OTEL_TRACES_SAMPLER";
    private static final String METRICS_EXPORTER = "OTEL_METRICS_EXPORTER";
    private static final String RESOURCE_ATTRIBUTES = "OTEL_RESOURCE_ATTRIBUTES";
    private static final String PROPAGATORS = "OTEL_PROPAGATORS";
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String BAGGAGE = "baggage";
    private static final String ENV_ATTR = "env.attr=1";
    private static final String SPI_ATTR = "spi.attr=from-spi";
    private static final String SPI_COUNT = "spi.count=2";
    private static final String TEST_METRICS = "test-metrics";
    private static final String TEST_SAMPLER = "test-sampler";
    private static final String TEST_HEADER = "x-test";

    // ---- Samplers ----------------------------------------------------------------------

    /** Matches {@code otel.traces.sampler=test-sampler} and always drops. */
    public static final class DroppingSamplerProvider implements ConfigurableSamplerProvider {
        @Override
        public Sampler createSampler(ConfigProperties config) {
            return Sampler.alwaysOff();
        }

        @Override
        public String getName() {
            return TEST_SAMPLER;
        }
    }

    /** Same name, registered second: must not replace the first match. */
    public static final class SecondSamplerProvider implements ConfigurableSamplerProvider {
        @Override
        public Sampler createSampler(ConfigProperties config) {
            return Sampler.alwaysOn();
        }

        @Override
        public String getName() {
            return TEST_SAMPLER;
        }
    }

    /** Matching provider that cannot create a sampler. */
    public static final class NullSamplerProvider implements ConfigurableSamplerProvider {
        @Override
        public Sampler createSampler(ConfigProperties config) {
            return null;
        }

        @Override
        public String getName() {
            return "null-sampler";
        }
    }

    // ---- Propagators -------------------------------------------------------------------

    /** Propagator writing a fixed header, to recognise it in a composite. */
    static final class HeaderPropagator implements TextMapPropagator {
        @Override
        public Collection<String> fields() {
            return List.of(TEST_HEADER);
        }

        @Override
        public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
            setter.set(carrier, TEST_HEADER, "injected");
        }

        @Override
        public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
            return context;
        }
    }

    /** Provides {@link HeaderPropagator} under the name {@code custom}. */
    public static final class CustomPropagatorProvider implements ConfigurablePropagatorProvider {
        @Override
        public TextMapPropagator getPropagator(ConfigProperties config) {
            return new HeaderPropagator();
        }

        @Override
        public String getName() {
            return "custom";
        }
    }

    /** Provider without a propagator: ignored. */
    public static final class NullPropagatorProvider implements ConfigurablePropagatorProvider {
        @Override
        public TextMapPropagator getPropagator(ConfigProperties config) {
            return null;
        }

        @Override
        public String getName() {
            return "null-propagator";
        }
    }

    // ---- Metric exporters --------------------------------------------------------------

    /** Provides an in-memory metric exporter under the name {@code test-metrics}. */
    public static final class InMemoryMetricExporterProvider implements ConfigurableMetricExporterProvider {
        @Override
        public MetricExporter createExporter(ConfigProperties config) {
            return InMemoryMetricExporter.create();
        }

        @Override
        public String getName() {
            return TEST_METRICS;
        }
    }

    /** Matching name but no exporter. */
    public static final class NullMetricExporterProvider implements ConfigurableMetricExporterProvider {
        @Override
        public MetricExporter createExporter(ConfigProperties config) {
            return null;
        }

        @Override
        public String getName() {
            return TEST_METRICS;
        }
    }

    // ---- Resources ---------------------------------------------------------------------

    /** Contributes {@code spi.attr=from-spi}. */
    public static final class AttributeResourceProvider implements ResourceProvider {
        @Override
        public Resource createResource(ConfigProperties config) {
            return Resource.create(Attributes.of(AttributeKey.stringKey("spi.attr"), "from-spi",
                    AttributeKey.longKey("spi.count"), 2L));
        }
    }

    /** Contributes nothing. */
    public static final class NullResourceProvider implements ResourceProvider {
        @Override
        public Resource createResource(ConfigProperties config) {
            return null;
        }
    }

    // ---- Customizers -------------------------------------------------------------------

    /** Adds a property, a resource attribute and a propagator replacement. */
    public static final class EverythingCustomizerProvider implements AutoConfigurationCustomizerProvider {
        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
            customizer.addPropertiesSupplier(() -> Map.of("otel.logs.exporter", "supplied"));
            customizer.addResourceCustomizer((resource, _) -> resource.merge(
                    Resource.create(Attributes.of(AttributeKey.stringKey("customized"), "yes"))));
            customizer.addPropagatorCustomizer((_, _) -> new HeaderPropagator());
        }
    }

    /** Registers only a side-effect callback: the propagator is kept. */
    public static final class SamplerOnlyCustomizerProvider implements AutoConfigurationCustomizerProvider {
        @Override
        public void customize(AutoConfigurationCustomizer customizer) {
            customizer.addSamplerCustomizer((sampler, _) -> sampler);
        }
    }

    // ---- Broken providers --------------------------------------------------------------

    /** Its constructor fails: the provider is skipped, the next ones still load. */
    public static final class FailingResourceProvider implements ResourceProvider {
        public FailingResourceProvider() {
            throw new IllegalStateException("cannot be created");
        }

        @Override
        public Resource createResource(ConfigProperties config) {
            return Resource.empty();
        }
    }

    // ---- Helpers -----------------------------------------------------------------------

    private static URLClassLoader loaderWith(Path dir, Map<Class<?>, List<String>> services) throws IOException {
        Path folder = dir.resolve("META-INF").resolve("services");
        Files.createDirectories(folder);
        for (var e : services.entrySet()) {
            Files.writeString(folder.resolve(e.getKey().getName()), String.join("\n", e.getValue()) + "\n");
        }
        return new URLClassLoader(new URL[] {dir.toUri().toURL()},
                OtelSpiProvidersDiscoveryTest.class.getClassLoader());
    }

    private static Map<String, String> injected(TextMapPropagator propagator) {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.root(), carrier, Map::put);
        return carrier;
    }

    // ---- Tests -------------------------------------------------------------------------

    @Test
    void sampler_provider_matching_the_configured_name_is_bridged(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(ConfigurableSamplerProvider.class, List.of(
                DroppingSamplerProvider.class.getName(), SecondSamplerProvider.class.getName(),
                NullSamplerProvider.class.getName())))) {
            var result = OtelSpiAutoConfiguration.discover(Map.of(TRACES_SAMPLER, TEST_SAMPLER), loader);

            var sampler = assertInstanceOf(OtelSamplerBridge.class, result.samplerOverride());
            assertEquals(SamplingResult.Decision.DROP, sampler.shouldSample(Context.root(), TRACE_ID, "op",
                    SpanKind.INTERNAL, Attributes.empty(), List.of()).decision());
            assertEquals(TEST_SAMPLER, result.env().get(TRACES_SAMPLER));
        }
    }

    @Test
    void sampler_provider_without_a_sampler_gives_no_override(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(ConfigurableSamplerProvider.class, List.of(
                NullSamplerProvider.class.getName())))) {
            assertNull(OtelSpiAutoConfiguration.discover(
                    Map.of(TRACES_SAMPLER, "null-sampler"), loader).samplerOverride());
            assertNull(OtelSpiAutoConfiguration.discover(
                    Map.of(TRACES_SAMPLER, "unknown"), loader).samplerOverride());
        }
    }

    @Test
    void propagators_compose_builtins_and_spi_providers(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(ConfigurablePropagatorProvider.class, List.of(
                CustomPropagatorProvider.class.getName(), NullPropagatorProvider.class.getName())))) {
            var result = OtelSpiAutoConfiguration.discover(Map.of(PROPAGATORS,
                    "tracecontext, baggage,b3,b3multi,jaeger,,custom,null-propagator,unknown"), loader);

            Collection<String> fields = result.propagatorsOverride().getTextMapPropagator().fields();
            assertTrue(fields.containsAll(List.of("traceparent", BAGGAGE, "b3", "X-B3-TraceId",
                    "uber-trace-id", TEST_HEADER)), "fields: " + fields);
        }
    }

    @Test
    void mp_telemetry_propagators_property_is_accepted() {
        var result = OtelSpiAutoConfiguration.discover(Map.of("MP_TELEMETRY_PROPAGATORS", BAGGAGE),
                new URLClassLoader(new URL[0], null));
        assertEquals(List.of(BAGGAGE), List.copyOf(result.propagatorsOverride().getTextMapPropagator().fields()));
    }

    @Test
    void propagators_without_any_usable_name_give_no_override() {
        URLClassLoader empty = new URLClassLoader(new URL[0], null);
        assertNull(OtelSpiAutoConfiguration.discover(Map.of(PROPAGATORS, " , "), empty).propagatorsOverride());
        assertNull(OtelSpiAutoConfiguration.discover(Map.of(PROPAGATORS, "unknown"), empty).propagatorsOverride());
    }

    @Test
    void metric_exporter_provider_is_bridged_and_neutralizes_the_native_exporter(@TempDir Path dir)
            throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(ConfigurableMetricExporterProvider.class, List.of(
                InMemoryMetricExporterProvider.class.getName(), NullMetricExporterProvider.class.getName())))) {
            var result = OtelSpiAutoConfiguration.discover(Map.of(METRICS_EXPORTER, TEST_METRICS), loader);

            assertEquals(1, result.extraMetricExporters().size());
            assertInstanceOf(OtelMetricExporterBridge.class, result.extraMetricExporters().get(0));
            assertEquals("none", result.env().get(METRICS_EXPORTER));
        }
    }

    @Test
    void unmatched_metric_exporter_name_keeps_the_configured_exporter(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(ConfigurableMetricExporterProvider.class, List.of(
                InMemoryMetricExporterProvider.class.getName())))) {
            var result = OtelSpiAutoConfiguration.discover(Map.of(METRICS_EXPORTER, "otlp"), loader);

            assertTrue(result.extraMetricExporters().isEmpty());
            assertEquals("otlp", result.env().get(METRICS_EXPORTER));
        }
    }

    @Test
    void resource_providers_are_appended_to_the_resource_attributes(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(ResourceProvider.class, List.of(
                FailingResourceProvider.class.getName(), NullResourceProvider.class.getName(),
                AttributeResourceProvider.class.getName())))) {
            var fresh = OtelSpiAutoConfiguration.discover(Map.of(), loader);
            assertEquals(List.of(SPI_ATTR, SPI_COUNT),
                    sorted(fresh.env().get(RESOURCE_ATTRIBUTES)));

            var merged = OtelSpiAutoConfiguration.discover(Map.of(RESOURCE_ATTRIBUTES, ENV_ATTR), loader);
            assertEquals(List.of(ENV_ATTR, SPI_ATTR, SPI_COUNT),
                    sorted(merged.env().get(RESOURCE_ATTRIBUTES)));

            var emptyExisting = OtelSpiAutoConfiguration.discover(Map.of(RESOURCE_ATTRIBUTES, ""), loader);
            assertEquals(List.of(SPI_ATTR, SPI_COUNT),
                    sorted(emptyExisting.env().get(RESOURCE_ATTRIBUTES)));
        }
    }

    @Test
    void customizers_contribute_properties_resource_and_propagator(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(AutoConfigurationCustomizerProvider.class, List.of(
                EverythingCustomizerProvider.class.getName())))) {
            var result = OtelSpiAutoConfiguration.discover(Map.of(RESOURCE_ATTRIBUTES, ENV_ATTR), loader);

            assertEquals("supplied", result.env().get("OTEL_LOGS_EXPORTER"));
            assertEquals(List.of("customized=yes", ENV_ATTR), sorted(result.env().get(RESOURCE_ATTRIBUTES)));
            assertNotNull(result.propagatorsOverride());
            assertEquals(Map.of(TEST_HEADER, "injected"), injected(result.propagatorsOverride().getTextMapPropagator()));

            var withoutEnvAttrs = OtelSpiAutoConfiguration.discover(Map.of(), loader);
            assertEquals("customized=yes", withoutEnvAttrs.env().get(RESOURCE_ATTRIBUTES));
        }
    }

    @Test
    void customizer_keeping_the_propagator_gives_no_override(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(AutoConfigurationCustomizerProvider.class, List.of(
                SamplerOnlyCustomizerProvider.class.getName())))) {
            assertNull(OtelSpiAutoConfiguration.discover(Map.of(), loader).propagatorsOverride());
        }
    }

    @Test
    void customizer_applies_on_top_of_spi_propagators(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(AutoConfigurationCustomizerProvider.class, List.of(
                EverythingCustomizerProvider.class.getName())))) {
            var result = OtelSpiAutoConfiguration.discover(Map.of(PROPAGATORS, "tracecontext"), loader);
            assertEquals(List.of(TEST_HEADER), List.copyOf(result.propagatorsOverride().getTextMapPropagator().fields()));
        }
    }

    @Test
    void unknown_provider_class_aborts_only_its_own_discovery(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = loaderWith(dir, Map.of(
                ResourceProvider.class, List.of("io.vidocq.humboldt.otel.interop.DoesNotExist"),
                ConfigurableSamplerProvider.class, List.of(DroppingSamplerProvider.class.getName())))) {
            var result = OtelSpiAutoConfiguration.discover(Map.of(TRACES_SAMPLER, TEST_SAMPLER), loader);

            assertNull(result.env().get(RESOURCE_ATTRIBUTES));
            assertNotNull(result.samplerOverride());
        }
    }

    private static List<String> sorted(String csv) {
        assertNotNull(csv);
        return List.of(csv.split(",")).stream().sorted().toList();
    }
}
