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
    @DisplayName("the dev console and its panel SPI stay, so that a panel of the application is the console's type")
    void theDevConsoleAndItsPanelSpiStay(@TempDir Path dir) throws Exception {
        var app = explodedModule(dir, "acme.app", List.of("io.vidocq.runtime.spi.devconsole",
                "io.vidocq.runtime.extensions.essentials.devconsole"), "acme.app.Main");
        var spi = explodedModule(dir, "io.vidocq.runtime.spi.devconsole", List.of(),
                "io.vidocq.runtime.spi.devconsole.Panel");
        var console = explodedModule(dir, "io.vidocq.runtime.extensions.essentials.devconsole",
                List.of("io.vidocq.runtime.spi.devconsole"), "io.vidocq.runtime.extensions.essentials.devconsole.Console");

        var config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, spi, console), ModuleFinder.of(), Set.of("acme.app"));

        assertEquals(Set.of(real(app)), relayered(VidocqAppLayer.applicationPaths(config, "acme.app")),
                "a panel SPI re-layered with the application would be a second DevConsolePanel type, which the"
                        + " console, kept in the boot layer, would no longer recognise after a reload");
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

    @Test
    @DisplayName("the langchain4j-cdi MCP extension keeps the MCP server and its invoker in the boot layer: only the application moves")
    void theMcpExtensionKeepsTheMcpServerInTheBootLayer(@TempDir Path dir) throws Exception {
        var app = explodedModule(dir, "acme.mcp.app", List.of(MCP_SERVER), "acme.mcp.app.Main");
        var server = explodedModule(dir, MCP_SERVER, List.of(), "dev.langchain4j.cdi.mcp.server.transport.Endpoint");
        var invoker = explodedModule(dir, MCP_INVOKER, List.of(MCP_SERVER),
                "dev.langchain4j.cdi.mcp.invoker.cdi41.Provider");
        var extension = explodedModule(dir, MCP_EXTENSION, List.of(MCP_SERVER, MCP_INVOKER),
                "io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.McpExtension");

        var config = ModuleLayer.boot().configuration().resolve(ModuleFinder.of(app, server, invoker, extension),
                ModuleFinder.of(), Set.of("acme.mcp.app", MCP_EXTENSION));

        assertEquals(Set.of(real(app)), relayered(VidocqAppLayer.applicationPaths(config, "acme.mcp.app")),
                "the extension is kept by the io.vidocq.runtime.extensions prefix, and a kept module keeps what it "
                        + "reads: the MCP server and the invoker stay in the boot layer (Vidocq/vidocq#94, option A)");
    }

    @Test
    @DisplayName("without the extension, the MCP server and its invoker move with the application")
    void withoutTheExtensionTheMcpServerMovesWithTheApplication(@TempDir Path dir) throws Exception {
        var app = explodedModule(dir, "acme.mcp.app", List.of(MCP_SERVER, MCP_INVOKER), "acme.mcp.app.Main");
        var server = explodedModule(dir, MCP_SERVER, List.of(), "dev.langchain4j.cdi.mcp.server.transport.Endpoint");
        var invoker = explodedModule(dir, MCP_INVOKER, List.of(MCP_SERVER),
                "dev.langchain4j.cdi.mcp.invoker.cdi41.Provider");

        var config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, server, invoker), ModuleFinder.of(), Set.of("acme.mcp.app"));

        assertEquals(Set.of(real(app), real(server), real(invoker)),
                relayered(VidocqAppLayer.applicationPaths(config, "acme.mcp.app")),
                "nothing kept reads them: the trampoline re-layers the MCP server, the case the extension changes");
    }

    // ---------------------------------------------------------------- fixtures

    private static final String MCP_SERVER = "dev.langchain4j.cdi.mcp.server";
    private static final String MCP_INVOKER = "dev.langchain4j.cdi.mcp.invoker.cdi41";
    private static final String MCP_EXTENSION = "io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp";

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
