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
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jars for the dependency codegen tests: compiled libraries and the fixture processors of the test resources. */
public final class DepgenFixtures {

    /** The annotations the fixture processors look for. */
    public static final Map<String, String> ANNOTATIONS = Map.of(
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

    private DepgenFixtures() {
    }

    /**
     * Compiles {@code sources} (relative path → content) under {@code dir} and jars the classes plus {@code extra}
     * entries into {@code dir/<name>.jar}.
     */
    public static Path jar(Path dir, String name, Map<String, String> sources, List<Path> classpath,
                           Map<String, String> extra) throws IOException {
        Path src = Files.createDirectories(dir.resolve(name + "-src"));
        Path classes = Files.createDirectories(dir.resolve(name + "-classes"));
        List<Path> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path file = src.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file);
        }
        if (!files.isEmpty()) {
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null,
                    StandardCharsets.UTF_8)) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                boolean ok = javac.getTask(null, fm, diagnostics, List.of("-proc:none"), null,
                        fm.getJavaFileObjectsFromPaths(files)).call();
                assertTrue(ok, () -> "fixture does not compile: " + diagnostics.getDiagnostics());
            }
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

    /** A jar holding the fixture processor {@code fixture.proc.<processor>}, compiled from the test resources. */
    public static Path processorJar(Path dir, String processor) throws IOException {
        String source;
        try (InputStream in = DepgenFixtures.class.getResourceAsStream("/depgen/processors/" + processor + ".java")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return jar(dir, processor, Map.of("fixture/proc/" + processor + ".java", source), List.of(),
                Map.of("META-INF/services/javax.annotation.processing.Processor", "fixture.proc." + processor + "\n"));
    }

    /** Every top-level class of {@code jar}, base entries only, without module and package infos. */
    public static List<String> topLevelClasses(Path jar) throws IOException {
        try (var file = new java.util.jar.JarFile(jar.toFile())) {
            return file.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/"))
                    .filter(n -> {
                        String simple = n.substring(n.lastIndexOf('/') + 1, n.length() - ".class".length());
                        return !simple.contains("$") && !simple.equals("module-info") && !simple.equals("package-info");
                    })
                    .map(n -> n.substring(0, n.length() - ".class".length()).replace('/', '.'))
                    .sorted()
                    .toList();
        }
    }
}
