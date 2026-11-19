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

import io.opentelemetry.context.Context;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.vidocq.humboldt.Humboldt;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.PeriodicMetricReader;
import io.vidocq.humboldt.sdk.trace.BatchSpanProcessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261004-01: OpenTelemetry components load their services through
 * {@code ConfigProperties.getComponentLoader()}. Upstream, the default loader is
 * {@code ServiceLoaderComponentLoader}, which humboldt-otel-context places in the explicit module
 * {@code io.opentelemetry.context}: a {@code ServiceLoader.load} from there fails on the module path with
 * "does not declare `uses`". {@link MapConfigProperties} must hand out a loader whose lookups work there.
 *
 * <p>Surefire runs this module's tests on the class path (where every service lookup works), so this test
 * builds a real {@link ModuleLayer} from the module jars — humboldt-otel-interop as an explicit module next
 * to {@code io.opentelemetry.context}, {@code io.opentelemetry.api}, the Humboldt SDK modules and the
 * OpenTelemetry autoconfigure SPI and trace propagators as automatic modules — and looks services up from
 * inside that layer, as an application on the module path would.</p>
 */
class MapConfigPropertiesModuleLayerTest {

    private static final String INTEROP = "io.vidocq.humboldt.otel.interop";

    private static ClassLoader layerLoader;

    @BeforeAll
    static void defineTheModuleLayer() {
        Path[] modulePath = Stream.of(
                        MapConfigProperties.class,     // humboldt-otel-interop (explicit module)
                        Context.class,                 // humboldt-otel-context: io.opentelemetry.context
                        io.opentelemetry.api.OpenTelemetry.class, // humboldt-otel-api: io.opentelemetry.api
                        Humboldt.class,                // humboldt-api
                        Resource.class,                // humboldt-sdk-common
                        BatchSpanProcessor.class,      // humboldt-sdk-trace
                        PeriodicMetricReader.class,    // humboldt-sdk-metric
                        W3CPropagators.class,          // humboldt-propagator-w3c
                        ConfigProperties.class,        // opentelemetry-sdk-extension-autoconfigure-spi (automatic)
                        B3Propagator.class)            // opentelemetry-extension-trace-propagators (automatic)
                .map(MapConfigPropertiesModuleLayerTest::locationOf)
                .distinct()
                .toArray(Path[]::new);
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration().resolve(
                ModuleFinder.of(modulePath), ModuleFinder.of(),
                Set.of(INTEROP, "io.opentelemetry.sdk.autoconfigure.spi", "io.opentelemetry.extension.trace.propagation"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        layerLoader = layer.findLoader(INTEROP);
        assertFalse(layer.findModule("io.opentelemetry.context").orElseThrow().getDescriptor().isAutomatic(),
                "io.opentelemetry.context must be the explicit Humboldt module");
    }

    @Test
    void the_component_loader_finds_services_the_interop_module_uses() throws Exception {
        List<?> providers = loadThroughTheComponentLoader(
                layerLoader.loadClass("io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider"));

        Method getName = layerLoader.loadClass("io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider")
                .getMethod("getName");
        List<Object> names = providers.stream().map(p -> invoke(getName, p)).toList();
        assertTrue(names.containsAll(List.of("b3", "b3multi", "jaeger")), "propagator providers found: " + names);
    }

    @Test
    void the_component_loader_finds_services_no_module_declares() throws Exception {
        // Neither humboldt-otel-interop nor io.opentelemetry.context declares `uses` for this service — the
        // case of an exporter looking up its HttpSenderProvider.
        List<?> tools = loadThroughTheComponentLoader(ToolProvider.class);

        List<String> names = tools.stream().map(t -> ((ToolProvider) t).name()).toList();
        assertTrue(names.contains("javac"), "tool providers found: " + names);
    }

    @Test
    void the_component_loader_class_is_owned_by_the_context_module() throws Exception {
        Object componentLoader = componentLoaderOf(newMapConfigProperties());

        assertEquals("io.opentelemetry.context", componentLoader.getClass().getModule().getName(),
                "the default loader is Humboldt's, which adds the uses of the services it loads (BUG-20261004-04)");
    }

    private static List<?> loadThroughTheComponentLoader(Class<?> service) throws Exception {
        Object componentLoader = componentLoaderOf(newMapConfigProperties());
        Class<?> componentLoaderType = layerLoader.loadClass("io.opentelemetry.common.ComponentLoader");
        Method loadList = componentLoaderType.getMethod("loadList", componentLoaderType, Class.class);
        try {
            return (List<?>) loadList.invoke(null, componentLoader, service);
        } catch (InvocationTargetException e) {
            throw new AssertionError("service lookup failed on the module path: " + e.getCause(), e.getCause());
        }
    }

    private static Object newMapConfigProperties() throws Exception {
        return layerLoader.loadClass(INTEROP + ".MapConfigProperties")
                .getConstructor(Map.class)
                .newInstance(Map.of());
    }

    private static Object componentLoaderOf(Object configProperties) throws Exception {
        return layerLoader.loadClass("io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties")
                .getMethod("getComponentLoader")
                .invoke(configProperties);
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
