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
package io.vidocq.runtime.core;

import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.classloader.VaubanLayerFactory;
import io.vidocq.vauban.classloader.spi.PluginContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The start-up list of extensions names every Vidocq extension on the module path: the JWT, Rest Client, Fault
 * Tolerance and Config extensions bring only a CDI Build Compatible Extension, no {@link VidocqExtension}, and the
 * list left them out although they worked (BUG-20261030-17, item 6).
 */
class ExtensionLoaderTest {

    private static VidocqExtension extension(String name, int priority) {
        return new VidocqExtension() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }
        };
    }

    @Test
    void theExtensionsWithoutLifecycleAreListedToo() {
        Map<String, String> descriptors = new LinkedHashMap<>();
        descriptors.put("io.vidocq.runtime.extensions.microprofile.cervantes", "cervantes-jwt");
        descriptors.put("io.vidocq.runtime.extensions.microprofile.ravel", "ravel-config");

        List<String> lines = ExtensionLoader.report(List.of(extension("chappe-webserver", 100)), descriptors);

        assertEquals(List.of(
                "Discovered 3 extension(s):",
                "  - chappe-webserver (priority=100)",
                "  - cervantes-jwt (CDI only)",
                "  - ravel-config (CDI only)"), lines);
    }

    @Test
    void anExtensionWithALifecycleIsListedOnce() {
        Map<String, String> descriptors = Map.of("io.vidocq.runtime.extensions.microprofile.ravel", "ravel-config");

        List<String> lines = ExtensionLoader.report(List.of(extension("ravel-config", 50)), descriptors);

        assertEquals(List.of("Discovered 1 extension(s):", "  - ravel-config (priority=50)"), lines);
    }

    @Test
    void noExtensionAtAll() {
        assertEquals(List.of("No Vidocq extensions discovered"), ExtensionLoader.report(List.of(), Map.of()));
    }

    /**
     * An extension module the application layer holds is listed too: an extension that reads an application library
     * (the Vaadin extension reads {@code flow.server}) goes to that layer with it, and has no lifecycle that would
     * bring its layer to the list (BUG-20261114-11).
     */
    @Test
    void theExtensionModulesOfTheApplicationLayerAreListedToo(@TempDir Path dir) throws Exception {
        Path extension = module(dir, "acme.vaadin");
        Path descriptor = Files.createDirectories(extension.resolve("META-INF/vidocq")).resolve("extension.properties");
        Files.writeString(descriptor, "id=vaadin\n");
        // Vauban's loader serves the whole layer: asked for a module's file outside any package, it answers with the
        // first archive that has one, so a library would pass for the extension.
        var layer = VaubanLayerFactory.createAppLayer(List.of(extension, module(dir, "acme.library")),
                ModuleLayer.boot(), ClassLoader.getSystemClassLoader(), PluginContext.empty());
        try {
            assertEquals(Map.of("acme.vaadin", "vaadin"), ExtensionLoader.descriptors(layer.layer()));
            assertEquals("vaadin", ExtensionLoader.descriptors(List.of(), layer.layer()).get("acme.vaadin"));
            assertFalse(ExtensionLoader.descriptors(List.of(), null).containsKey("acme.vaadin"));
        } finally {
            layer.loader().close();
        }
    }

    /** Compiles an empty module named {@code name}. */
    private static Path module(Path dir, String name) throws Exception {
        Path module = Files.createDirectories(dir.resolve(name));
        Path source = Files.writeString(Files.createDirectories(dir.resolve("src-" + name)).resolve("module-info.java"),
                "module " + name + " {}\n");
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        assertEquals(0, javac.run(System.out, System.err, "-d", module.toString(), source.toString()));
        return module;
    }
}
