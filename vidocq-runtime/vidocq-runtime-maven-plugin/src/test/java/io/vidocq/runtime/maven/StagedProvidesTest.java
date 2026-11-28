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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("vidocq:jlink — the staged modules gain the provides their services files declare")
class StagedProvidesTest {

    private static final String SERVICE = "java.util.spi.ToolProvider";

    @TempDir
    Path dir;

    @Test
    @DisplayName("a services file becomes a provides, next to the provides the module declared")
    void aServicesFileBecomesAProvides() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path module = compile(stage, "acme.tool", "provides java.sql.Driver with acme.tool.Sql;", "Tool", "Sql");
        service(module, SERVICE, "# generated", "acme.tool.Tool");

        var added = StagedProvides.promote(stage);

        assertEquals(Map.of("acme.tool", Map.of(SERVICE, List.of("acme.tool.Tool"))), added);
        assertEquals(Map.of(SERVICE, List.of("acme.tool.Tool"), "java.sql.Driver", List.of("acme.tool.Sql")),
                provides(stage, "acme.tool"));
        // The services file stays: the same jar still works under the application layer.
        assertTrue(Files.exists(module.resolve("META-INF/services/" + SERVICE)));
    }

    @Test
    @DisplayName("a service type the module cannot read, and a provider of another archive, are not promoted")
    void whatTheModuleSystemWouldRefuseIsNotPromoted() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path module = compile(stage, "acme.tool", "", "Tool");
        // reactor-core lists a micrometer type that is often absent: jlink refuses a provides of such a type.
        service(module, "io.micrometer.context.ContextAccessor", "acme.tool.Tool");
        service(module, SERVICE, "elsewhere.Tool");

        assertEquals(Map.of(), StagedProvides.promote(stage));
        assertEquals(Map.of(), provides(stage, "acme.tool"));
    }

    @Test
    @DisplayName("a module that already declares what its services files say is left untouched")
    void aModuleThatDeclaresItAllIsLeftUntouched() throws Exception {
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path module = compile(stage, "acme.tool", "provides " + SERVICE + " with acme.tool.Tool;", "Tool");
        service(module, SERVICE, "acme.tool.Tool");
        byte[] before = Files.readAllBytes(module.resolve("module-info.class"));

        assertEquals(Map.of(), StagedProvides.promote(stage));
        assertEquals(List.of(ByteBuffer.wrap(before)),
                List.of(ByteBuffer.wrap(Files.readAllBytes(module.resolve("module-info.class")))));
    }

    @Test
    @DisplayName("in a multi-release jar, every module-info.class gains the provides")
    void everyDescriptorOfAMultiReleaseJarGainsTheProvides() throws Exception {
        Path classes = compile(Files.createDirectories(dir.resolve("classes")), "acme.tool", "", "Tool");
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path jar = stage.resolve("acme-tool.jar");
        byte[] descriptor = Files.readAllBytes(classes.resolve("module-info.class"));
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            entry(out, "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\nMulti-Release: true\n".getBytes(StandardCharsets.UTF_8));
            entry(out, "module-info.class", descriptor);
            entry(out, "META-INF/versions/11/module-info.class", descriptor);
            entry(out, "acme/tool/Tool.class", Files.readAllBytes(classes.resolve("acme/tool/Tool.class")));
            entry(out, "META-INF/services/" + SERVICE, "acme.tool.Tool\n".getBytes(StandardCharsets.UTF_8));
        }

        StagedProvides.promote(stage);

        try (var zip = FileSystems.newFileSystem(jar)) {
            for (String entry : List.of("module-info.class", "META-INF/versions/11/module-info.class")) {
                var read = ModuleDescriptor.read(ByteBuffer.wrap(Files.readAllBytes(zip.getPath(entry))));
                assertEquals(1, read.provides().size(), entry);
            }
        }
    }

    @Test
    @DisplayName("bytes that are not a module descriptor are refused")
    void bytesThatAreNotADescriptorAreRefused() throws Exception {
        Path classes = compile(Files.createDirectories(dir.resolve("classes")), "acme.tool", "", "Tool");
        byte[] notADescriptor = Files.readAllBytes(classes.resolve("acme/tool/Tool.class"));
        Map<String, List<String>> none = Map.of();

        assertThrows(IllegalArgumentException.class, () -> StagedProvides.withProvides(notADescriptor, none));
    }

    private static Map<String, List<String>> provides(Path stage, String module) {
        var provides = new java.util.TreeMap<String, List<String>>();
        ModuleFinder.of(stage).find(module).orElseThrow().descriptor().provides()
                .forEach(p -> provides.put(p.service(), p.providers()));
        return provides;
    }

    private static void entry(JarOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static void service(Path module, String service, String... lines) throws IOException {
        Path services = Files.createDirectories(module.resolve("META-INF/services"));
        Files.write(services.resolve(service), List.of(lines));
    }

    /**
     * Compiles into {@code parent/<name>} a module {@code name} that requires {@code java.sql}, with the given
     * directives, and one class per simple name: a {@code ToolProvider} that is a {@code java.sql.Driver} too.
     */
    private Path compile(Path parent, String name, String directives, String... classes) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src-" + name + "-" + parent.getFileName()));
        Path out = Files.createDirectories(parent.resolve(name));
        Files.writeString(src.resolve("module-info.java"),
                "module " + name + " { requires java.sql; " + directives + " }\n");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), src.resolve("module-info.java").toString()));
        Path pkg = Files.createDirectories(src.resolve(name.replace('.', '/')));
        for (String simple : classes) {
            Path file = pkg.resolve(simple + ".java");
            Files.writeString(file, "package " + name + ";\n"
                    + "public class " + simple + " implements java.util.spi.ToolProvider, java.sql.Driver {\n"
                    + "  public String name() { return \"" + simple + "\"; }\n"
                    + "  public int run(java.io.PrintWriter o, java.io.PrintWriter e, String... a) { return 0; }\n"
                    + "  public java.sql.Connection connect(String u, java.util.Properties p) { return null; }\n"
                    + "  public boolean acceptsURL(String u) { return false; }\n"
                    + "  public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {"
                    + " return null; }\n"
                    + "  public int getMajorVersion() { return 1; }\n"
                    + "  public int getMinorVersion() { return 0; }\n"
                    + "  public boolean jdbcCompliant() { return false; }\n"
                    + "  public java.util.logging.Logger getParentLogger() { return null; }\n"
                    + "}\n");
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                args.toArray(String[]::new)), name);
        return out;
    }
}
