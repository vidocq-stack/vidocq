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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyCodegenTest {

    private static final int RELEASE = Runtime.version().feature();

    private static final Map<String, String> ANNOTATIONS = DepgenFixtures.ANNOTATIONS;

    @TempDir
    Path dir;

    // --- fixtures ---------------------------------------------------------------------------------------------

    /** Compiles {@code sources} (relative path → content) and jars the classes plus {@code extra} entries. */
    private Path jar(String name, Map<String, String> sources, List<Path> classpath, Map<String, String> extra)
            throws IOException {
        return DepgenFixtures.jar(dir, name, sources, classpath, extra);
    }

    /** A brick whose processor is the fixture {@code fixture.proc.<processor>}, compiled from the test resources. */
    private Brick brick(String name, String processor) throws IOException {
        return new Brick(new DependencyCodegenHints(name, List.of(), List.of(), List.of(), List.of()),
                "io.vidocq:" + name.toLowerCase() + "-codegen:1.0",
                List.of(DepgenFixtures.processorJar(dir, processor)));
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
        return DependencyCodegen.run(jar, List.of(bricks), DepgenFixtures.topLevelClasses(jar), List.of(jar), RELEASE,
                "com.acme:lib",
                dir.resolve("target/vidocq-depgen/lib.log"), "shown/lib.log");
    }

    private static String text(DependencyCodegen.Output output, String path) {
        byte[] bytes = output.files().get(path);
        assertNotNull(bytes, () -> path + " not generated, got " + output.files().keySet());
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
    void aBrickErrorNamesOnlyThatBrick() {
        var failure = assertThrows(DependencyCodegen.Failure.class,
                () -> run(acme(), brick("Alpha", "ProcessorA"), brick("Epsilon", "ProcessorE")));

        assertEquals(1, failure.lines().size(), () -> failure.lines().toString());
        String line = failure.lines().getFirst();
        assertTrue(line.startsWith("vidocq:generate: lib-1.0.jar — Epsilon cannot process com.acme.Foo: "), line);
        assertTrue(!line.contains("Alpha"), line);
    }

    @Test
    void aProcessorThatThrowsAMultiLineMessageFailsWithOneLine() {
        var failure = assertThrows(DependencyCodegen.Failure.class, () -> run(acme(), brick("Tau", "ProcessorT")));

        assertEquals(1, failure.lines().size(), () -> failure.lines().toString());
        String line = failure.lines().getFirst();
        assertTrue(!line.contains("\n") && !line.contains("second line"), line);
        assertTrue(line.startsWith("vidocq:generate: lib-1.0.jar — Tau cannot process the jar: a processor threw"
                + " java.lang.IllegalStateException: broken state. Fix the library"), line);
    }

    private static Set<Path> depgenTempDirs() throws IOException {
        try (var dirs = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return dirs.filter(p -> p.getFileName().toString().startsWith("vidocq-depgen-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void anErrorFromAProcessorFailsWithOneLineAndLeavesNoTemporaryDirectory() throws Exception {
        Path jar = acme();
        Brick broken = brick("Xi", "ProcessorX");
        Set<Path> before = depgenTempDirs();

        var failure = assertThrows(DependencyCodegen.Failure.class, () -> run(jar, broken));

        assertEquals(1, failure.lines().size(), () -> failure.lines().toString());
        assertTrue(failure.lines().getFirst().contains("Xi cannot process the jar: a processor threw"
                + " java.lang.StackOverflowError: processor recursion"), failure.lines()::toString);
        Set<Path> leaked = new java.util.HashSet<>(depgenTempDirs());
        leaked.removeAll(before);
        assertEquals(Set.of(), leaked, "the temporary directory of the run is deleted");
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
