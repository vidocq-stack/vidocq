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

import io.vidocq.vauban.classloader.VaubanLayerFactory;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq.run from an IDE with vidocq.app.layer=all (spec §4, tests 1 and 6). */
@DisplayName("Vidocq.run — vidocq.app.layer=all")
class VidocqAppLayerAllTest {

    @Test
    @DisplayName("the reload's thread warning suggests vidocq.app.layer.keep only under all")
    void theThreadWarningSuggestsAPinOnlyUnderAll() {
        String all = VidocqAppLayer.leakedThreadsWarning(List.of("acme-pages-poller"), LayerSplit.Mode.ALL);
        String classes = VidocqAppLayer.leakedThreadsWarning(List.of("acme-pages-poller"), LayerSplit.Mode.CLASSES);

        assertTrue(all.contains("acme-pages-poller"), all);
        assertTrue(all.endsWith("Stop them when the application stops, or keep their library in the parent layer"
                + " with vidocq.app.layer.keep."), all);
        assertTrue(classes.contains("1 thread(s) still hold the previous application layer"), classes);
        assertTrue(classes.contains("acme-pages-poller"), classes);
        assertTrue(classes.endsWith("Stop them when the application stops."), classes);
        assertFalse(classes.contains("vidocq.app.layer.keep"), classes);
    }

    @Test
    @DisplayName("a pinned module and what it reads stay; only the application moves")
    void anExtensionClosureStays(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("acme.web"), "acme.app.Main");
        var web = TestModules.explodedModule(dir, "acme.web", List.of(), "acme.web.Page");
        var ext = TestModules.explodedModule(dir, "org.acme.ext", List.of("acme.web"), "org.acme.ext.Ext");
        Configuration config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, web, ext), ModuleFinder.of(), Set.of("acme.app", "org.acme.ext"));

        var pinned = new LinkedHashMap<String, String>();
        var moved = VidocqAppLayer.applicationPaths(config, "acme.app", LayerSplit.Mode.ALL,
                List.of("org.acme.ext"), pinned);

        assertEquals(Set.of(app.toRealPath()), real(moved),
                "org.acme.ext is pinned, and it reads acme.web: both stay, only the application moves");
        assertEquals(Map.of("org.acme.ext", "vidocq.app.layer.keep"), pinned);
    }

    @Test
    @DisplayName("an automatic module stays in the boot layer: its twin would clash")
    void automaticModulesStay(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("flowserver"), "acme.app.Main");
        var flow = TestModules.automaticJar(dir, "flowserver.jar", "com.vaadin.flow.Server");
        Configuration config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, flow), ModuleFinder.of(), Set.of("acme.app"));

        var moved = VidocqAppLayer.applicationPaths(config, "acme.app", LayerSplit.Mode.ALL, List.of(),
                new LinkedHashMap<>());

        assertEquals(Set.of(app.toRealPath()), real(moved));
    }

    @Test
    @DisplayName("classes keeps today's policy")
    void classesIsToday(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("acme.pricing"), "acme.app.Main");
        var pricing = TestModules.explodedModule(dir, "acme.pricing", List.of(), "acme.pricing.Price");
        Configuration config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, pricing), ModuleFinder.of(), Set.of("acme.app"));

        assertEquals(real(VidocqAppLayer.applicationPaths(config, "acme.app")),
                real(VidocqAppLayer.applicationPaths(config, "acme.app", LayerSplit.Mode.CLASSES, List.of(),
                        new LinkedHashMap<>())));
    }

    @Test
    @DisplayName("a library read by a brick and by the application is defined once: the application gets the brick's Class")
    void aSharedLibraryIsDefinedOnce(@TempDir Path dir) throws Exception {
        var app = TestModules.explodedModule(dir, "acme.app", List.of("acme.codec"), "acme.app.Main");
        var brick = TestModules.explodedModule(dir, "io.vidocq.fake.brick", List.of("acme.codec"),
                "io.vidocq.fake.brick.Brick");
        var codec = TestModules.explodedModule(dir, "acme.codec", List.of(), "acme.codec.Codec");
        var candidates = List.of(LayerSplit.candidate(app, null), LayerSplit.candidate(brick, null),
                LayerSplit.candidate(codec, null));
        var split = LayerSplit.split(candidates, Set.of("acme.app"), List.of(), Map.of());

        Configuration parentConfig = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(split.parentPaths().toArray(Path[]::new)), ModuleFinder.of(), split.parentNames());
        ModuleLayer parent = ModuleLayer.boot().defineModulesWithOneLoader(parentConfig,
                ClassLoader.getSystemClassLoader());
        var appLayer = VaubanLayerFactory.createAppLayer(split.applicationPaths(), parent,
                parent.findLoader("acme.codec"), PluginContext.empty());
        try {
            assertEquals(Set.of("acme.app"), appLayer.moduleNames());
            assertSame(parent.findLoader("acme.codec").loadClass("acme.codec.Codec"),
                    Class.forName("acme.codec.Codec", false, appLayer.loader()),
                    "one Codec class: the application layer reads the parent's");
        } finally {
            appLayer.loader().close();
        }
    }

    private static Set<Path> real(List<Path> paths) {
        return paths.stream().map(p -> {
            try {
                return p.toRealPath();
            } catch (java.io.IOException _) {
                return p.toAbsolutePath().normalize();
            }
        }).collect(java.util.stream.Collectors.toSet());
    }
}
