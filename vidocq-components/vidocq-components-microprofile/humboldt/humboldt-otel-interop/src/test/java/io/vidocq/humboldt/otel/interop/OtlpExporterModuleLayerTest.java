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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenTelemetry OTLP exporter ({@code opentelemetry-exporter-otlp} 1.66, which an application adds to export
 * through the OpenTelemetry autoconfigure SPI) on the module path, next to the Humboldt explicit modules
 * {@code io.opentelemetry.api} and {@code io.opentelemetry.context}.
 *
 * <p>BUG-20261004-03: the OpenTelemetry SDK and exporter jars use {@code io.opentelemetry.api.internal} across
 * jars. Upstream the API jar is an automatic module and exports every package; Humboldt's explicit
 * {@code io.opentelemetry.api} must export that package to them, and to them only.</p>
 *
 * <p>Surefire runs this module's tests on the class path, where no module check applies, so each test defines
 * a real {@link ModuleLayer} from the jars — Humboldt's explicit modules plus the OpenTelemetry jars as
 * automatic modules — and drives the exporter from inside it, as an application on the module path would. Each
 * test gets a layer of its own: a static initializer that fails in one layer must not affect the next test.</p>
 */
class OtlpExporterModuleLayerTest {

    private static final String INTEROP = "io.vidocq.humboldt.otel.interop";
    private static final String API_INTERNAL = "io.opentelemetry.api.internal";
    private static final String OTLP_INTERNAL = "io.opentelemetry.exporter.otlp.internal.";

    /** One class per jar of the module path; the jar is the location the class was loaded from. */
    private static final List<String> MODULE_PATH_ANCHORS = List.of(
            // Humboldt explicit modules
            INTEROP + ".MapConfigProperties",                                  // humboldt-otel-interop
            "io.opentelemetry.context.Context",                                // humboldt-otel-context
            "io.opentelemetry.api.OpenTelemetry",                              // humboldt-otel-api
            "io.vidocq.humboldt.Humboldt",                                     // humboldt-api
            "io.vidocq.humboldt.sdk.common.Resource",                          // humboldt-sdk-common
            "io.vidocq.humboldt.sdk.trace.BatchSpanProcessor",                 // humboldt-sdk-trace
            "io.vidocq.humboldt.sdk.metric.PeriodicMetricReader",              // humboldt-sdk-metric
            "io.vidocq.humboldt.propagator.w3c.W3CPropagators",                // humboldt-propagator-w3c
            // OpenTelemetry 1.66 jars (automatic modules)
            "io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties",         // opentelemetry-sdk-extension-autoconfigure-spi
            "io.opentelemetry.sdk.OpenTelemetrySdk",                           // opentelemetry-sdk
            "io.opentelemetry.sdk.common.CompletableResultCode",               // opentelemetry-sdk-common
            "io.opentelemetry.sdk.trace.SdkTracerProvider",                    // opentelemetry-sdk-trace
            "io.opentelemetry.sdk.metrics.SdkMeterProvider",                   // opentelemetry-sdk-metrics
            "io.opentelemetry.sdk.logs.SdkLoggerProvider",                     // opentelemetry-sdk-logs
            "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter",  // opentelemetry-exporter-otlp
            "io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler", // opentelemetry-exporter-otlp-common
            "io.opentelemetry.exporter.internal.SenderUtil",                   // opentelemetry-exporter-common
            "io.opentelemetry.exporter.sender.jdk.internal.JdkHttpSenderProvider"); // opentelemetry-exporter-sender-jdk

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "OtlpSpanExporterProvider, OtlpHttpSpanExporter",
            "OtlpMetricExporterProvider, OtlpHttpMetricExporter",
            "OtlpLogRecordExporterProvider, OtlpHttpLogRecordExporter"})
    void the_otlp_exporter_provider_creates_its_exporter_on_the_module_path(String provider, String exporter) {
        ModuleLayer layer = defineTheModuleLayer();

        Object created = createExporter(layer, provider, Map.of());

        assertEquals(exporter, created.getClass().getSimpleName());
        shutdown(created);
    }

    /**
     * BUG-20261004-04: any configured compression initialises the exporter's {@code CompressorUtil}, whose static
     * registry loads the {@code Compressor} services through {@code ComponentLoader.forClassLoader(...)}, the
     * upstream default that no {@code ConfigProperties} can replace — a {@code ServiceLoader.load} issued from
     * {@code io.opentelemetry.context}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"gzip", "none"})
    void the_otlp_exporter_provider_applies_a_configured_compression_on_the_module_path(String compression) {
        ModuleLayer layer = defineTheModuleLayer();

        Object created = createExporter(layer, "OtlpSpanExporterProvider",
                Map.of("otel.exporter.otlp.compression", compression));

        assertEquals("OtlpHttpSpanExporter", created.getClass().getSimpleName());
        assertTrue(created.toString().contains("compressorEncoding=" + ("none".equals(compression) ? "null" : compression)),
                created.toString());
        shutdown(created);
    }

    /**
     * An exporter built directly, without {@code setComponentLoader(...)}, loads its sender and its compressor
     * through the upstream default {@code ComponentLoader.forClassLoader(...)} — the residual of
     * BUG-20261004-01 that BUG-20261004-04 closes.
     */
    @Test
    void an_otlp_exporter_built_without_a_component_loader_finds_its_sender_and_compressor_on_the_module_path() {
        ModuleLayer layer = defineTheModuleLayer();
        ClassLoader loader = layer.findLoader(INTEROP);

        Object created = onTheModulePath("build an exporter with the default component loader", () -> {
            Object builder = loader.loadClass("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter")
                    .getMethod("builder").invoke(null);
            builder.getClass().getMethod("setCompression", String.class).invoke(builder, "gzip");
            return builder.getClass().getMethod("build").invoke(builder);
        });

        assertEquals("OtlpHttpSpanExporter", created.getClass().getSimpleName());
        assertTrue(created.toString().contains("compressorEncoding=gzip"), created.toString());
        shutdown(created);
    }

    @Test
    void io_opentelemetry_api_internal_is_exported_only_to_the_opentelemetry_modules_that_use_it() {
        ModuleLayer layer = defineTheModuleLayer();
        Module api = module(layer, "io.opentelemetry.api");

        assertFalse(api.isExported(API_INTERNAL), "the export must stay qualified");
        assertFalse(api.isExported(API_INTERNAL, module(layer, INTEROP)), "no Humboldt module uses it");
        assertFalse(api.isExported(API_INTERNAL, module(layer, "io.opentelemetry.sdk")),
                "opentelemetry-sdk does not use it");
        assertTrue(api.isExported(API_INTERNAL, module(layer, "io.opentelemetry.sdk.autoconfigure.spi")));
        assertTrue(api.isExported(API_INTERNAL, module(layer, "io.opentelemetry.exporter.otlp")));
    }

    /**
     * Lets the upstream exporter provider create its exporter from a humboldt-otel-interop
     * {@link MapConfigProperties} — the path humboldt-otel-interop's discovery takes.
     */
    private static Object createExporter(ModuleLayer layer, String providerName, Map<String, String> extra) {
        ClassLoader loader = layer.findLoader(INTEROP);
        Map<String, String> properties = new HashMap<>();
        properties.put("otel.exporter.otlp.protocol", "http/protobuf");
        properties.put("otel.exporter.otlp.endpoint", "http://127.0.0.1:4318");
        properties.putAll(extra);
        return onTheModulePath("create the exporter of " + providerName, () -> {
            Object config = loader.loadClass(INTEROP + ".MapConfigProperties")
                    .getConstructor(Map.class, ClassLoader.class)
                    .newInstance(properties, loader);
            Class<?> configType = loader.loadClass("io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties");
            Object provider = loader.loadClass(OTLP_INTERNAL + providerName).getConstructor().newInstance();
            return provider.getClass().getMethod("createExporter", configType).invoke(provider, config);
        });
    }

    private static void shutdown(Object exporter) {
        onTheModulePath("shut the exporter down", () -> exporter.getClass().getMethod("shutdown").invoke(exporter));
    }

    private static ModuleLayer defineTheModuleLayer() {
        Path[] modulePath = MODULE_PATH_ANCHORS.stream()
                .map(OtlpExporterModuleLayerTest::locationOf)
                .distinct()
                .toArray(Path[]::new);
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration().resolve(
                ModuleFinder.of(modulePath), ModuleFinder.of(),
                Set.of(INTEROP, "io.opentelemetry.exporter.otlp", "io.opentelemetry.exporter.sender.jdk.internal"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        assertFalse(module(layer, "io.opentelemetry.api").getDescriptor().isAutomatic(),
                "io.opentelemetry.api must be the explicit Humboldt module");
        assertFalse(module(layer, "io.opentelemetry.context").getDescriptor().isAutomatic(),
                "io.opentelemetry.context must be the explicit Humboldt module");
        return layer;
    }

    private static Module module(ModuleLayer layer, String name) {
        return layer.findModule(name).orElseThrow(() -> new AssertionError("module not in the layer: " + name));
    }

    private static Object onTheModulePath(String what, ReflectiveCall call) {
        try {
            return call.run();
        } catch (InvocationTargetException e) {
            throw new AssertionError("failed to " + what + " on the module path: " + causes(e.getCause()),
                    e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        for (Throwable c = t.getCause(); c != null && c != c.getCause(); c = c.getCause()) {
            sb.append(" <- caused by ").append(c);
        }
        return sb.toString();
    }

    private static Path locationOf(String className) {
        try {
            Class<?> type = Class.forName(className, false, OtlpExporterModuleLayerTest.class.getClassLoader());
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException("cannot locate the jar of " + className, e);
        }
    }

    @FunctionalInterface
    private interface ReflectiveCall {
        Object run() throws ReflectiveOperationException;
    }
}
