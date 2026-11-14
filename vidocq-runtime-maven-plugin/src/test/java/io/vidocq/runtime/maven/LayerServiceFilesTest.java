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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module of the image's application layer keeps its providers, in its services files, and loses the
 * {@code provides} that would bind it into the boot layer (BUG-20261114-11).
 */
class LayerServiceFilesTest {

    private static final String SERVICE = "java.util.spi.ToolProvider";

    @TempDir
    Path dir;

    @Test
    void aJarKeepsItsProvidersInItsServicesFilesOnly() throws Exception {
        Path classes = compile("acme.tool", "acme.tool.Tool", "acme.tool.Other");
        Path jar = jar(classes, Map.of("META-INF/services/" + SERVICE, "# a comment\nacme.tool.Other\n"));

        var moved = LayerServiceFiles.move(jar);

        assertEquals(Map.of(SERVICE, List.of("acme.tool.Tool", "acme.tool.Other")), moved);
        var descriptor = ModuleFinder.of(jar).find("acme.tool").orElseThrow().descriptor();
        assertTrue(descriptor.provides().isEmpty(), descriptor.toString());
        assertEquals(List.of("java.util.spi.ToolProvider"), List.copyOf(descriptor.uses()));
        assertTrue(descriptor.exports().stream().anyMatch(e -> e.source().equals("acme.tool")), descriptor.toString());
        try (var zip = FileSystems.newFileSystem(jar)) {
            // Merged with what the jar listed, once each.
            assertEquals(List.of("acme.tool.Other", "acme.tool.Tool"),
                    Files.readAllLines(zip.getPath("META-INF/services/" + SERVICE), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aMultiReleaseJarLosesTheProvidesOfEveryDescriptor() throws Exception {
        Path classes = compile("acme.tool", "acme.tool.Tool");
        byte[] descriptor = Files.readAllBytes(classes.resolve("module-info.class"));
        Path jar = jar(classes, Map.of());
        try (var zip = FileSystems.newFileSystem(jar)) {
            Path versioned = zip.getPath("META-INF/versions/11/module-info.class");
            Files.createDirectories(versioned.getParent());
            Files.write(versioned, descriptor);
        }

        LayerServiceFiles.move(jar);

        try (var zip = FileSystems.newFileSystem(jar)) {
            for (String entry : List.of("module-info.class", "META-INF/versions/11/module-info.class")) {
                var read = ModuleDescriptor.read(java.nio.ByteBuffer.wrap(Files.readAllBytes(zip.getPath(entry))));
                assertTrue(read.provides().isEmpty(), entry);
            }
        }
    }

    @Test
    void anExplodedModuleIsRewrittenInPlace() throws Exception {
        Path classes = compile("acme.tool", "acme.tool.Tool");

        LayerServiceFiles.move(classes);

        assertTrue(ModuleFinder.of(classes).find("acme.tool").orElseThrow().descriptor().provides().isEmpty());
        assertEquals(List.of("acme.tool.Tool"), Files.readAllLines(classes.resolve("META-INF/services/" + SERVICE)));
    }

    @Test
    void aDescriptorWithoutProvidesIsLeftAsItIs() throws Exception {
        Path classes = compile("acme.plain", null);
        byte[] before = Files.readAllBytes(classes.resolve("module-info.class"));

        assertSame(before, LayerServiceFiles.withoutProvides(before));
        assertEquals(Map.of(), LayerServiceFiles.move(classes));
    }

    @Test
    void aModuleWithoutDescriptorFails() throws Exception {
        Path empty = Files.createDirectories(dir.resolve("empty"));

        var failure = assertThrows(IOException.class, () -> LayerServiceFiles.move(empty));

        assertTrue(failure.getMessage().contains("module-info.class"), failure.getMessage());
        byte[] notADescriptor = Files.readAllBytes(compile("acme.tool", "acme.tool.Tool").resolve("acme/tool/Tool.class"));
        assertThrows(IllegalArgumentException.class, () -> LayerServiceFiles.provides(notADescriptor));
    }

    /**
     * Compiles a module {@code name} that uses {@code ToolProvider} and provides it with each of {@code providers},
     * or a module without directives when {@code providers} is {@code null}.
     */
    private Path compile(String name, String... providers) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src-" + name));
        Path out = Files.createDirectories(dir.resolve(name));
        String pkg = name;
        StringBuilder info = new StringBuilder("module " + name + " {\n");
        if (providers != null && providers[0] != null) {
            info.append("  exports ").append(pkg).append(";\n  uses ").append(SERVICE).append(";\n  provides ")
                    .append(SERVICE).append(" with ").append(String.join(", ", providers)).append(";\n");
            Path dirOfPackage = Files.createDirectories(src.resolve(pkg.replace('.', '/')));
            for (String provider : providers) {
                String simple = provider.substring(provider.lastIndexOf('.') + 1);
                Files.writeString(dirOfPackage.resolve(simple + ".java"), "package " + pkg + ";\n"
                        + "public class " + simple + " implements java.util.spi.ToolProvider {\n"
                        + "  public String name() { return \"" + simple + "\"; }\n"
                        + "  public int run(java.io.PrintWriter o, java.io.PrintWriter e, String... a) { return 0; }\n"
                        + "}\n");
            }
        }
        Files.writeString(src.resolve("module-info.java"), info.append("}\n").toString());
        List<String> args = new java.util.ArrayList<>(List.of("-d", out.toString()));
        try (var files = Files.walk(src)) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
        }
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        assertEquals(0, javac.run(System.out, System.err, args.toArray(String[]::new)), name);
        return out;
    }

    /** A jar of {@code classes}, with the extra text entries. */
    private Path jar(Path classes, Map<String, String> extra) throws Exception {
        Path jar = dir.resolve(classes.getFileName() + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
            for (var entry : extra.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
