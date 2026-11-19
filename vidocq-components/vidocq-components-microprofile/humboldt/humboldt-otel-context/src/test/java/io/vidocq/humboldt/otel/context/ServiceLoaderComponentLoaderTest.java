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
package io.vidocq.humboldt.otel.context;

import io.opentelemetry.common.ComponentLoader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Humboldt replaces the upstream {@code io.opentelemetry.common.ServiceLoaderComponentLoader} (BUG-20261004-04):
 * on the class path it must keep the OpenTelemetry 1.66 contract. Its module-path behaviour is covered by
 * humboldt-otel-interop's {@code OtlpExporterModuleLayerTest}, on the shaded jar.
 */
class ServiceLoaderComponentLoaderTest {

    @Test
    void the_default_component_loader_keeps_the_upstream_contract() {
        ClassLoader classLoader = getClass().getClassLoader();

        ComponentLoader componentLoader = ComponentLoader.forClassLoader(classLoader);

        Class<?> type = componentLoader.getClass();
        assertEquals("io.opentelemetry.common.ServiceLoaderComponentLoader", type.getName());
        assertFalse(Modifier.isPublic(type.getModifiers()), "package-private, as upstream");
        assertEquals("ServiceLoaderComponentLoader{classLoader=" + classLoader + "}", componentLoader.toString());
        List<String> tools = ComponentLoader.loadList(componentLoader, ToolProvider.class).stream()
                .map(ToolProvider::name)
                .toList();
        assertTrue(tools.contains("javac"), "services of the class loader: " + tools);
    }
}
