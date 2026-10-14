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
package io.vidocq.runtime.maven.depgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyCodegenTest {

    private static final int RELEASE = Runtime.version().feature();

    private static final Map<String, String> ANNOTATIONS = Map.of(
            "fixture/Marked.java", """
                    package fixture;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                    public @interface Marked {}
                    """,
            "fixture/Other.java", """
                    package fixture;
                    public @interface Other {}
                    """);

    @TempDir
    Path dir;

    // --- fixtures ---------------------------------------------------------------------------------------------

    /** Compiles {@code sources} (relative path → content) and jars the classes plus {@code extra} entries. */
    private Path jar(String name, Map<String, String> sources, List<Path> classpath, Map<String, String> extra)
            throws IOException {
        Path src = Files.createDirectories(dir.resolve(name + "-src"));
        Path classes = Files.createDirectories(dir.resolve(name + "-classes"));
        List<Path> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path file = src.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            boolean ok = javac.getTask(null, fm, diagnostics, List.of("-proc:none"), null,
                    fm.getJavaFileObjectsFromPaths(files)).call();
            assertTrue(ok, () -> "fixture does not compile: " + diagnostics.getDiagnostics());
        }
        Path jar = dir.resolve(name + ".jar");
        try (OutputStream os = Files.newOutputStream(jar); var out = new JarOutputStream(os);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
            for (var e : extra.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** A brick whose processor is the fixture {@code fixture.proc.<processor>}, compiled from the test resources. */
    private Brick brick(String name, String processor) throws IOException {
        String source;
        try (InputStream in = getClass().getResourceAsStream("/depgen/processors/" + processor + ".java")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path jar = jar(processor, Map.of("fixture/proc/" + processor + ".java", source), List.of(),
                Map.of("META-INF/services/javax.annotation.processing.Processor", "fixture.proc." + processor + "\n"));
        return new Brick(new DependencyCodegenHints(name, List.of(), List.of(), List.of(), List.of()),
                "io.vidocq:" + name.toLowerCase() + "-codegen:1.0", List.of(jar));
    }

    private Path lib(Map<String, String> classes) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(ANNOTATIONS);
        sources.putAll(classes);
        return jar("lib-1.0", sources, List.of(), Map.of());
    }

    private Path acme() throws IOException {
        return lib(Map.of(
                "com/acme/Foo.java", "package com.acme; @fixture.Marked public class Foo {}",
                "com/acme/Bar.java", "package com.acme; @fixture.Other public class Bar {}",
                "com/acme/Plain.java", "package com.acme; public class Plain {}",
                "com/acme/package-info.java", "package com.acme;"));
    }

    private DependencyCodegen.Output run(Path jar, Brick... bricks) throws Exception {
        return DependencyCodegen.run(jar, List.of(bricks), List.of(jar), RELEASE, "com.acme:lib",
                dir.resolve("target/vidocq-depgen/lib.log"), "shown/lib.log");
    }

    private static String text(DependencyCodegen.Output output, String path) {
        byte[] bytes = output.files().get(path);
        assertTrue(bytes != null, () -> path + " not generated, got " + output.files().keySet());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // --- tests -------------------------------------------------------------------------------------------------

    @Test
    void generatesClassesAndResources() throws Exception {
        var output = run(acme(), brick("Alpha", "ProcessorA"));

        assertTrue(output.files().containsKey("com/acme/Foo$$Gen.class"), () -> output.files().keySet().toString());
        assertEquals("com.acme.Foo\n", text(output, "META-INF/fixture/a.list"));
        assertEquals(2, output.files().size(), () -> output.files().keySet().toString());
    }

    @Test
    void twoBricksRunTogether() throws Exception {
        var output = run(acme(), brick("Alpha", "ProcessorA"), brick("Beta", "ProcessorB"));

        assertEquals("com.acme.Foo\n", text(output, "META-INF/fixture/a.list"));
        assertEquals("com.acme.Bar\n", text(output, "META-INF/fixture/b.list"));
        assertTrue(output.files().containsKey("com/acme/Foo$$Gen.class"));
    }

    @Test
    void nothingToGenerateGivesAnEmptyOutput() throws Exception {
        Path jar = lib(Map.of("com/acme/Plain.java", "package com.acme; public class Plain {}"));

        var output = run(jar, brick("Alpha", "ProcessorA"));

        assertEquals(Map.of(), output.files());
    }

    @Test
    void aProcessorErrorFailsWithOneLine() throws Exception {
        var failure = assertThrows(DependencyCodegen.Failure.class, () -> run(acme(), brick("Epsilon", "ProcessorE")));

        assertEquals(1, failure.lines().size(), () -> failure.lines().toString());
        String line = failure.lines().getFirst();
        assertEquals("vidocq:generate: lib-1.0.jar — Epsilon cannot process com.acme.Foo: cannot handle com.acme.Foo."
                + " Fix the library, or leave the jar out with <scanExcludes>com.acme:lib</scanExcludes>."
                + " Full javac output: shown/lib.log", line);
        Path log = dir.resolve("target/vidocq-depgen/lib.log");
        assertTrue(Files.readString(log).contains("cannot handle com.acme.Foo"), log::toString);
    }

    @Test
    void aBrickErrorNamesOnlyThatBrick() throws Exception {
        var failure = assertThrows(DependencyCodegen.Failure.class,
                () -> run(acme(), brick("Alpha", "ProcessorA"), brick("Epsilon", "ProcessorE")));

        assertEquals(1, failure.lines().size(), () -> failure.lines().toString());
        String line = failure.lines().getFirst();
        assertTrue(line.startsWith("vidocq:generate: lib-1.0.jar — Epsilon cannot process com.acme.Foo: "), line);
        assertTrue(!line.contains("Alpha"), line);
    }

    @Test
    void processorWarningsNameTheBrickAndTheElement() throws Exception {
        Path jar = lib(Map.of(
                "com/acme/Bar.java", "package com.acme; @fixture.Other public class Bar {}",
                "com/acme/Baz.java", """
                        package com.acme;
                        public class Baz {
                            @fixture.Other public String name;
                            @fixture.Other public void run() {}
                            @fixture.Other public static class Deep {}
                        }
                        """));

        var output = run(jar, brick("Omega", "ProcessorW"));

        String prefix = "vidocq:generate: lib-1.0.jar — Omega warns about ";
        assertEquals(Set.of(prefix + "com.acme.Bar: looks odd", prefix + "com.acme.Baz.name: looks odd",
                        prefix + "com.acme.Baz.run: looks odd", prefix + "com.acme.Baz$Deep: looks odd"),
                Set.copyOf(output.warnings()));
        assertEquals(4, output.warnings().size(), () -> output.warnings().toString());
        assertEquals(Map.of(), output.files());
    }

    @Test
    void nestedClassesAreNotPassedTwice() throws Exception {
        Path jar = lib(Map.of("com/acme/Outer.java", """
                package com.acme;
                @fixture.Marked public class Outer {
                    @fixture.Marked public static class Inner {}
                }
                """));

        var output = run(jar, brick("Alpha", "ProcessorA"));

        assertTrue(output.files().containsKey("com/acme/Outer$$Gen.class"), () -> output.files().keySet().toString());
        assertTrue(output.files().containsKey("com/acme/Inner$$Gen.class"), () -> output.files().keySet().toString());
        assertEquals("com.acme.Outer\ncom.acme.Outer.Inner\n", text(output, "META-INF/fixture/a.list"));
    }
}
