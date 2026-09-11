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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which boot-layer modules {@code Vidocq.run} re-layers. It applies Vauban's re-layer policy, the one
 * the Java SE launcher uses, with the Vidocq bricks as extra kept prefixes and the caller's module as
 * a root. A CDI-agnostic library that only the application reads therefore moves into the layer too,
 * and the Vauban loader can place its in-package client proxies (vauban#53).
 */
@DisplayName("Vidocq.run — which boot-layer modules are re-layered")
class VidocqAppLayerSelectionTest {

    @Test
    @DisplayName("a CDI-agnostic library the application reads moves with it; a runtime brick and what it reads stay")
    void cdiAgnosticLibrariesMoveRuntimeBricksStay(@TempDir Path dir) throws Exception {
        var app = explodedModule(dir, "acme.app", List.of("acme.pricing", "io.vidocq.cassini.fake"), "acme.app.Main");
        var pricing = explodedModule(dir, "acme.pricing", List.of(), "acme.pricing.Price");
        var brick = explodedModule(dir, "io.vidocq.cassini.fake", List.of("acme.codec"), "io.vidocq.cassini.fake.Rest");
        var codec = explodedModule(dir, "acme.codec", List.of(), "acme.codec.Codec");

        var config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, pricing, brick, codec), ModuleFinder.of(), Set.of("acme.app"));

        assertEquals(Set.of(real(app), real(pricing)), relayered(VidocqAppLayer.applicationPaths(config, "acme.app")),
                "acme.pricing carries no beans list, but only the application reads it: it moves, so its proxies "
                        + "can be placed. io.vidocq.cassini.fake is a runtime brick and stays; acme.codec is read by "
                        + "the brick and stays with it");
    }

    @Test
    @DisplayName("the caller's module moves even when its name falls under a runtime prefix, and what it reads moves too")
    void theCallerAlwaysMoves(@TempDir Path dir) throws Exception {
        var app = explodedModule(dir, "io.vidocq.cassini.demo", List.of("acme.pricing"), "io.vidocq.cassini.demo.Main");
        var pricing = explodedModule(dir, "acme.pricing", List.of(), "acme.pricing.Price");

        var config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, pricing), ModuleFinder.of(), Set.of("io.vidocq.cassini.demo"));

        assertEquals(Set.of(real(app), real(pricing)),
                relayered(VidocqAppLayer.applicationPaths(config, "io.vidocq.cassini.demo")),
                "the caller is the application: a runtime prefix must not keep it, nor, through it, what it reads");
    }

    // ---------------------------------------------------------------- fixtures

    private static Set<Path> relayered(List<Path> paths) {
        return paths.stream().map(VidocqAppLayerSelectionTest::real).collect(Collectors.toSet());
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** An exploded explicit module: a module-info requiring {@code requires}, and one empty class. */
    private static Path explodedModule(Path dir, String name, List<String> requires, String className)
            throws IOException {
        var root = dir.resolve(name);
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of(name), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    for (var required : requires) {
                        mb.requires(ModuleRequireInfo.of(ModuleDesc.of(required), 0, null));
                    }
                })));
        var classFile = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, ClassFile.of().build(ClassDesc.of(className),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
        return root;
    }
}
