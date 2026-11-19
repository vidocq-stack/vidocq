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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.maven.depgen.Brick;
import io.vidocq.runtime.maven.depgen.DependencyCodegenHints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.vidocq.runtime.maven.EnrichedJarsBrickTest.WEB_FILE;
import static io.vidocq.runtime.maven.EnrichedJarsBrickTest.entry;
import static io.vidocq.runtime.maven.EnrichedJarsBrickTest.foyComponent;
import static io.vidocq.runtime.maven.EnrichedJarsBrickTest.jar;
import static io.vidocq.runtime.maven.EnrichedJarsBrickTest.manifestOf;
import static io.vidocq.runtime.maven.EnrichedJarsBrickTest.text;
import static io.vidocq.runtime.maven.EnrichedJarsTest.descriptor;
import static io.vidocq.runtime.maven.EnrichedJarsTest.descriptorOf;
import static io.vidocq.runtime.maven.EnrichedJarsTest.emptyClass;
import static io.vidocq.runtime.maven.EnrichedJarsTest.patch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rebase {@code vidocq:package} and {@code vidocq:jlink} run on a copy a brick enriched, after
 * {@code vauban:modularize} (vidocq#251).
 */
class DependencyBricksPackagingTest {

    static final Brick FOY = new Brick(new DependencyCodegenHints("Foy", List.of(), List.of(), List.of(),
            List.of(WEB_FILE), List.of()), "io.vidocq:foy-codegen:1.0", List.of());

    @TempDir
    Path tmp;

    /** A brick-selected jar without a descriptor, enriched by {@code vidocq:generate}, then modularized. */
    Path enrichedAndModularized(Path build) throws Exception {
        Path auto = jar(tmp.resolve("m2/flow-1.0.jar"), Map.of(
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "flow"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Servlet$$FoyComponent\n")));
        EnrichedJars.enrichAll(build,
                List.of(new EnrichedJars.Scanned(auto, "flow", "org.dep:flow:1.0", List.of("Foy"),
                        DependencyBricks.inputLines(List.of(FOY)))),
                List.of(), List.of(auto),
                new EnrichedJars.BrickMerge(DependencyBricks.mergedResources(List.of(FOY)), packages -> Set.of()),
                line -> { }, line -> { });
        jar(build.resolve("vauban-modularized/flow-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.flow"),
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet")), Map.of());
        return auto;
    }

    @Test
    void packageKeepsABrickSelectedAutomaticCopyAutomatic() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = enrichedAndModularized(build);
        assertNull(entry(EnrichedJars.resolve(build, auto), "module-info.class"), "generate kept it automatic");
        List<String> info = new ArrayList<>();

        new DependencyBricks.Packaging(build, () -> List.of(FOY), List.of(auto), line -> { }, false)
                .rebase(new EnrichedJars.Scanned(auto, "flow", "org.dep:flow:1.0"), info::add);

        Path copy = EnrichedJars.resolve(build, auto);
        assertNull(entry(copy, "module-info.class"),
                "the modularized descriptor can miss uses; the layer launch takes the automatic copy");
        assertTrue(descriptorOf(copy).isAutomatic());
        assertEquals("Foy", manifestOf(copy).getValue("Vidocq-Enriched-By"));
        assertTrue(info.stream().anyMatch(l -> l.contains("flow-1.0.jar") && l.contains("automatic")), info.toString());
    }

    /** The brick processors produced nothing, Vauban generated providers: generate still kept it automatic. */
    @Test
    void packageKeepsABrickSelectedCopyWithVaubanProvidersOnlyAutomatic() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/flow-1.0.jar"), Map.of(
                "org/dep/Bean.class", emptyClass("org.dep.Bean")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "flow"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents")));
        EnrichedJars.enrichAll(build, List.of(new EnrichedJars.Scanned(auto, "flow", "org.dep:flow:1.0", true)),
                List.of("org.dep._VaubanComponents"), List.of(auto), line -> { }, line -> { });
        jar(build.resolve("vauban-modularized/flow-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.flow"),
                "org/dep/Bean.class", emptyClass("org.dep.Bean")), Map.of());
        Path copy = EnrichedJars.resolve(build, auto);
        String inputs = manifestOf(copy).getValue("Vidocq-Enriched-Inputs");

        new DependencyBricks.Packaging(build, () -> List.of(FOY), List.of(auto), line -> { }, false)
                .rebase(new EnrichedJars.Scanned(auto, "flow", "org.dep:flow:1.0"), line -> { });

        assertNull(entry(copy, "module-info.class"), "kept as generate wrote it");
        assertEquals(inputs, manifestOf(copy).getValue("Vidocq-Enriched-Inputs"), "left untouched");
    }

    @Test
    void packageStillRebasesACopyNoBrickSelected() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "org/dep/Bean.class", emptyClass("org.dep.Bean")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents")));
        EnrichedJars.enrichAll(build, List.of(new EnrichedJars.Scanned(lib, "lib", "org.dep:lib:1.0")),
                List.of("org.dep._VaubanComponents"), List.of(lib), line -> { }, line -> { });
        jar(build.resolve("vauban-modularized/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.lib"),
                "org/dep/Bean.class", emptyClass("org.dep.Bean")), Map.of());

        new DependencyBricks.Packaging(build, () -> List.of(FOY), List.of(lib), line -> { }, false)
                .rebase(new EnrichedJars.Scanned(lib, "lib", "org.dep:lib:1.0"), line -> { });

        assertEquals("org.lib", descriptorOf(EnrichedJars.resolve(build, lib)).name(),
                "a jar no brick selected ships with the name vauban:modularize gave it");
    }

    @Test
    void jlinkRebasesABrickSelectedAutomaticCopyOnTheModularizedJar() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = enrichedAndModularized(build);

        new DependencyBricks.Packaging(build, () -> List.of(FOY), List.of(auto), line -> { }, true)
                .rebase(new EnrichedJars.Scanned(auto, "flow", "org.dep:flow:1.0"), line -> { });

        Path copy = EnrichedJars.resolve(build, auto);
        assertNotNull(entry(copy, "module-info.class"), "jlink refuses automatic modules");
        ModuleDescriptor md = descriptorOf(copy);
        assertEquals("org.flow", md.name());
        assertEquals("Foy", manifestOf(copy).getValue("Vidocq-Enriched-By"));
    }
}
