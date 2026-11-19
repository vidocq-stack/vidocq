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
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectingAutoConfigurationCustomizerTest {

    private static final String SERVICE_NAME = "OTEL_SERVICE_NAME";
    private static final String SERVICE = "svc";
    private static final Map<String, String> PROPS = Map.of("otel.service.name", SERVICE);

    @Test
    void empty_customizer_changes_nothing() {
        var customizer = new CollectingAutoConfigurationCustomizer();
        TextMapPropagator base = W3CTraceContextPropagator.getInstance();

        assertFalse(customizer.hasAny());
        assertEquals(Map.of(SERVICE_NAME, SERVICE), customizer.applyPropertyCustomizers(Map.of(SERVICE_NAME, SERVICE)));
        assertEquals("", customizer.applyResourceCustomizersAsAttrs(PROPS));
        assertSame(base, customizer.applyPropagatorCustomizers(base, PROPS));
    }

    private static boolean registers(Consumer<CollectingAutoConfigurationCustomizer> registration) {
        var customizer = new CollectingAutoConfigurationCustomizer();
        registration.accept(customizer);
        return customizer.hasAny();
    }

    @Test
    void every_add_method_registers_a_callback() {
        assertTrue(registers(c -> c.addResourceCustomizer((r, _) -> r)));
        assertTrue(registers(c -> c.addPropagatorCustomizer((p, _) -> p)));
        assertTrue(registers(c -> c.addPropertiesCustomizer(_ -> Map.of())));
        assertTrue(registers(c -> c.addPropertiesSupplier(Map::of)));
        assertTrue(registers(c -> c.addSamplerCustomizer((s, _) -> s)));
        assertTrue(registers(c -> c.addSpanExporterCustomizer((e, _) -> e)));
        assertTrue(registers(c -> c.addTracerProviderCustomizer((b, _) -> b)));
    }

    @Test
    void add_methods_return_the_customizer_itself() {
        var customizer = new CollectingAutoConfigurationCustomizer();
        assertSame(customizer, customizer.addSamplerCustomizer((s, _) -> s));
    }

    @Test
    void properties_suppliers_then_customizers_are_merged_in_env_form() {
        var customizer = new CollectingAutoConfigurationCustomizer();
        customizer.addPropertiesSupplier(() -> Map.of("otel.metrics.exporter", "supplied"));
        customizer.addPropertiesSupplier(() -> null);
        customizer.addPropertiesCustomizer(config -> Map.of(
                "otel.metrics.exporter", "customized",
                "otel.seen.service", String.valueOf(config.getString("otel.service.name"))));
        customizer.addPropertiesCustomizer(_ -> null);

        Map<String, String> merged = customizer.applyPropertyCustomizers(Map.of(SERVICE_NAME, SERVICE));

        assertEquals("customized", merged.get("OTEL_METRICS_EXPORTER"));
        assertEquals(SERVICE, merged.get("OTEL_SEEN_SERVICE"));
        assertEquals(SERVICE, merged.get(SERVICE_NAME));
    }

    @Test
    void resource_customizers_are_chained_into_csv_attributes() {
        var customizer = new CollectingAutoConfigurationCustomizer();
        customizer.addResourceCustomizer((resource, config) -> resource.merge(
                Resource.create(Attributes.of(AttributeKey.stringKey("a"), config.getString("otel.service.name")))));
        customizer.addResourceCustomizer((resource, _) -> resource.merge(
                Resource.create(Attributes.of(AttributeKey.longKey("b"), 2L))));

        String csv = customizer.applyResourceCustomizersAsAttrs(PROPS);

        assertEquals(List.of("a=svc", "b=2"), List.of(csv.split(",")).stream().sorted().toList());
    }

    @Test
    void propagator_customizers_are_chained() {
        var customizer = new CollectingAutoConfigurationCustomizer();
        TextMapPropagator replacement = TextMapPropagator.noop();
        List<TextMapPropagator> seen = new ArrayList<>();
        customizer.addPropagatorCustomizer((propagator, _) -> {
            seen.add(propagator);
            return replacement;
        });
        customizer.addPropagatorCustomizer((propagator, _) -> {
            seen.add(propagator);
            return propagator;
        });
        TextMapPropagator base = W3CTraceContextPropagator.getInstance();

        assertSame(replacement, customizer.applyPropagatorCustomizers(base, PROPS));
        assertEquals(List.of(base, replacement), seen);
    }

    @Test
    void side_effect_customizers_are_all_invoked_even_when_one_fails() {
        var customizer = new CollectingAutoConfigurationCustomizer();
        Map<String, Integer> calls = new HashMap<>();
        List<Boolean> placeholderResults = new ArrayList<>();
        customizer.addSamplerCustomizer((_, _) -> {
            throw new IllegalStateException("broken sampler customizer");
        });
        customizer.addSamplerCustomizer((sampler, _) -> {
            calls.merge("sampler", 1, Integer::sum);
            return sampler;
        });
        customizer.addSpanExporterCustomizer((_, _) -> {
            throw new IllegalStateException("broken exporter customizer");
        });
        customizer.addSpanExporterCustomizer((exporter, _) -> {
            calls.merge("exporter", 1, Integer::sum);
            for (CompletableResultCode code : List.of(exporter.export(List.of()), exporter.flush(),
                    exporter.shutdown())) {
                placeholderResults.add(code.isSuccess());
            }
            return exporter;
        });
        customizer.addTracerProviderCustomizer((_, _) -> {
            throw new IllegalStateException("broken tracer provider customizer");
        });
        customizer.addTracerProviderCustomizer((builder, _) -> {
            calls.merge("tracerProvider", 1, Integer::sum);
            return builder;
        });

        customizer.invokeSamplerCustomizers(PROPS);
        customizer.invokeSpanExporterCustomizers(PROPS);
        customizer.invokeTracerProviderCustomizers(PROPS);

        assertEquals(Map.of("sampler", 1, "exporter", 1, "tracerProvider", 1), calls);
        assertEquals(List.of(true, true, true), placeholderResults);
    }

    @Test
    void side_effect_invocations_without_customizers_do_nothing() {
        var customizer = new CollectingAutoConfigurationCustomizer();
        customizer.invokeSamplerCustomizers(PROPS);
        customizer.invokeSpanExporterCustomizers(PROPS);
        customizer.invokeTracerProviderCustomizers(PROPS);
        assertFalse(customizer.hasAny());
    }
}
