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

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the annotation processors of the bricks that want a dependency jar over that jar's compiled classes, the way
 * they run over the project's sources: an in-process {@code javac -proc:only} given the jar's top-level class names,
 * then a second {@code javac} that compiles the sources they generated.
 */
public final class DependencyCodegen {

    /**
     * What one jar produced: generated class files and resources, relative path → bytes, sorted by path; empty when
     * nothing.
     */
    public record Output(Map<String, byte[]> files) {
        public Output {
            files = Collections.unmodifiableSortedMap(new TreeMap<>(files));
        }
    }

    /** One line per problem, already worded for the build. */
    public static final class Failure extends Exception {
        private final List<String> lines;

        Failure(List<String> lines) {
            super(String.join("\n", lines));
            this.lines = List.copyOf(lines);
        }

        public List<String> lines() {
            return lines;
        }
    }

    private DependencyCodegen() {
    }

    /**
     * @param jar         the dependency jar
     * @param bricks      the bricks that want it (non-empty)
     * @param classpath   the jar's compile classpath: the project's dependency jars (the jar itself included)
     * @param release     the {@code --release} value
     * @param coordinates {@code groupId:artifactId} of the jar, for the {@code scanExcludes} hint of a failure line
     * @param artifactId  for the log file name
     * @param logDir      {@code target/vidocq-depgen/}, where {@code <artifactId>.log} receives javac's full output
     */
    public static Output run(Path jar, List<Brick> bricks, List<Path> classpath, int release, String coordinates,
                             String artifactId, Path logDir) throws Failure, IOException {
        if (bricks.isEmpty()) {
            throw new IllegalArgumentException("no brick for " + jar);
        }
        List<String> classNames = topLevelClasses(jar);
        if (classNames.isEmpty()) {
            return new Output(Map.of());
        }
        var problem = new Problem(jar, bricks, coordinates, artifactId);
        var log = new StringBuilder();
        Path tmp = Files.createTempDirectory("vidocq-depgen-");
        try {
            Path src = Files.createDirectories(tmp.resolve("src"));
            Path res = Files.createDirectories(tmp.resolve("res"));
            Path classes = Files.createDirectories(tmp.resolve("classes"));
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                throw new IllegalStateException("vidocq:generate needs a JDK: no system Java compiler");
            }
            List<Path> processorPath = bricks.stream().flatMap(b -> b.processorPath().stream()).distinct().toList();

            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            List<String> lines = new ArrayList<>();
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                fm.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, processorPath);
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(src));
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(res));
                boolean ok = compile(javac, fm, diagnostics, log, "annotation processing",
                        List.of("-proc:only", "-implicit:none", "--release", Integer.toString(release), "-Xlint:none"),
                        classNames, null, problem, lines);
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        lines.add(problem.line("cannot process " + source(fm, d), message(d)));
                    }
                }
                if (!ok && lines.isEmpty()) {
                    lines.add(problem.line("cannot process the jar", "javac failed"));
                }
            }
            if (lines.isEmpty()) {
                List<Path> generated = files(src).stream().filter(p -> p.toString().endsWith(".java")).toList();
                if (!generated.isEmpty()) {
                    var second = new DiagnosticCollector<JavaFileObject>();
                    try (StandardJavaFileManager fm = javac.getStandardFileManager(second, null, StandardCharsets.UTF_8)) {
                        fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                        boolean ok = compile(javac, fm, second, log, "compilation of the generated sources",
                                List.of("-proc:none", "--release", Integer.toString(release), "-Xlint:none"),
                                null, fm.getJavaFileObjectsFromPaths(generated), problem, lines);
                        if (lines.isEmpty() && !ok) {
                            String first = second.getDiagnostics().stream()
                                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                                    .map(DependencyCodegen::message)
                                    .findFirst().orElse("javac failed");
                            lines.add(problem.line("generated code that does not compile", first));
                        }
                    }
                }
            }
            Files.createDirectories(logDir);
            Files.writeString(logDir.resolve(artifactId + ".log"), log, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                throw new Failure(lines);
            }
            Map<String, byte[]> files = new TreeMap<>();
            for (Path root : List.of(res, classes)) {
                for (Path file : files(root)) {
                    files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            return new Output(files);
        } finally {
            delete(tmp);
        }
    }

    /**
     * Runs one javac task, appends its whole output to {@code log}; a processor that throws becomes a failure line.
     *
     * @return whether javac succeeded
     */
    private static boolean compile(JavaCompiler javac, StandardJavaFileManager fm,
                                   DiagnosticCollector<JavaFileObject> diagnostics, StringBuilder log, String phase,
                                   List<String> options, List<String> classNames,
                                   Iterable<? extends JavaFileObject> sources, Problem problem, List<String> lines) {
        var out = new StringWriter();
        boolean ok;
        try {
            ok = javac.getTask(out, fm, diagnostics, options, classNames, sources).call();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            out.append("processor threw ").append(cause.toString()).append('\n');
            lines.add(problem.line("cannot process the jar", "a processor threw " + cause));
            ok = false;
        }
        log.append("== ").append(phase).append(": javac ").append(String.join(" ", options));
        if (classNames != null) {
            log.append(' ').append(String.join(" ", classNames));
        }
        log.append('\n');
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            log.append(d.getKind()).append(": ");
            if (d.getSource() != null) {
                log.append(d.getSource().getName()).append(": ");
            }
            log.append(d.getMessage(Locale.ROOT)).append('\n');
        }
        log.append(out);
        return ok;
    }

    /** The binary names of the jar's top-level classes, base entries only, without module and package infos. */
    static List<String> topLevelClasses(Path jar) throws IOException {
        try (var file = new JarFile(jar.toFile())) {
            return file.stream()
                    .map(e -> e.getName())
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

    /** The class a diagnostic is about, or "the jar". */
    private static String source(StandardJavaFileManager fm, Diagnostic<? extends JavaFileObject> d) {
        JavaFileObject source = d.getSource();
        if (source == null) {
            return "the jar";
        }
        String name = fm.inferBinaryName(StandardLocation.CLASS_PATH, source);
        return name != null ? name : source.getName();
    }

    private static String message(Diagnostic<?> d) {
        String message = d.getMessage(Locale.ROOT);
        int eol = message.indexOf('\n');
        return (eol < 0 ? message : message.substring(0, eol)).strip();
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Words the failure lines of one jar. */
    private record Problem(Path jar, List<Brick> bricks, String coordinates, String artifactId) {
        String line(String what, String message) {
            String names = bricks.stream().map(b -> b.hints().brick())
                    .distinct().collect(Collectors.joining(", "));
            return "vidocq:generate: " + jar.getFileName() + " — " + names + " " + what + ": " + message
                    + ". Fix the library, or leave the jar out with <scanExcludes>" + coordinates
                    + "</scanExcludes>. Full javac output: target/vidocq-depgen/" + artifactId + ".log";
        }
    }
}
