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

import javax.annotation.processing.Processor;
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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the annotation processors of the bricks that want a dependency jar over that jar's compiled classes, the way
 * they run over the project's sources: an in-process {@code javac -proc:only} given the top-level class names the
 * selection found for them,
 * then a second {@code javac} that compiles the sources they generated. Each brick's processors are loaded from
 * its own processor path and wrapped, so that an error or warning names the brick that reported it and the element.
 */
public final class DependencyCodegen {

    /**
     * What one jar produced: generated class files and resources, relative path → bytes, sorted by path; empty when
     * nothing.
     */
    public record Output(Map<String, byte[]> files, List<String> warnings) {
        public Output {
            warnings = List.copyOf(warnings);
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
     * @param classNames  the binary names of the top-level classes the processors see: those the selection found
     *                    for the bricks ({@code DependencyCodegenSelection.Match#classes}); none runs nothing
     * @param classpath   the jar's compile classpath: the project's dependency jars (the jar itself included)
     * @param release     the {@code --release} value
     * @param coordinates {@code groupId:artifactId} of the jar, for the {@code scanExcludes} hint of a failure line
     * @param log         the file that receives javac's full output, {@code target/vidocq-depgen/<artifactId>.log}
     * @param logDisplay  {@code log} as a failure line prints it, relative to the project's base directory
     */
    public static Output run(Path jar, List<Brick> bricks, List<String> classNames, List<Path> classpath,
                             int release, String coordinates, Path log, String logDisplay)
            throws Failure, IOException {
        if (bricks.isEmpty()) {
            throw new IllegalArgumentException("no brick for " + jar);
        }
        // Only the classes the hints point at: passing every class made javac fail on classes that need an optional
        // API absent from the build, such as atmosphere-runtime's websocket endpoints (spec §4.1).
        if (classNames.isEmpty()) {
            return new Output(Map.of(), List.of());
        }
        var problem = new Problem(jar, coordinates, logDisplay);
        String allBricks = bricks.stream().map(b -> b.hints().brick()).distinct().collect(Collectors.joining(", "));
        var javacOutput = new StringBuilder();
        Path tmp = ownerOnlyTempDirectory("vidocq-depgen-");
        Output output;
        try {
            Path src = Files.createDirectories(tmp.resolve("src"));
            Path res = Files.createDirectories(tmp.resolve("res"));
            Path classes = Files.createDirectories(tmp.resolve("classes"));
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                throw new IllegalStateException("vidocq:generate needs a JDK: no system Java compiler");
            }

            List<String> lines = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var processors = new AttributedProcessors(bricks);
                 StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(src));
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(res));
                Result result = processors.reports().isEmpty()
                        ? compile(javac, fm, diagnostics, javacOutput, "annotation processing",
                                List.of("-proc:only", "-implicit:none", "--release", Integer.toString(release),
                                        "-Xlint:none"),
                                classNames, null, processors.processors())
                        : new Result(false, null);
                // What a processor reported names its brick and the element; javac shows it again as a diagnostic
                // without a source, so those diagnostics are skipped once each.
                List<String> reported = new ArrayList<>();
                for (AttributedProcessors.Report report : processors.reports()) {
                    if (report.isError()) {
                        reported.add(report.message());
                        lines.add(problem.line(report.brick(), "cannot process " + report.what(),
                                firstLine(report.message())));
                    } else {
                        warnings.add(problem.warning(report.brick(), report.what(), firstLine(report.message())));
                    }
                }
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR && !reported.remove(d.getMessage(Locale.ROOT))) {
                        lines.add(problem.line(allBricks, "cannot process the jar", message(d)));
                    }
                }
                if (result.thrown() != null) {
                    String brick = processors.thrownBy() != null ? processors.thrownBy() : allBricks;
                    lines.add(problem.line(brick, "cannot process the jar", "a processor threw " + result.thrown()));
                }
                if (!result.ok() && lines.isEmpty()) {
                    lines.add(problem.line(allBricks, "cannot process the jar", "javac failed"));
                }
            }
            if (lines.isEmpty()) {
                List<Path> generated = files(src).stream().filter(p -> p.toString().endsWith(".java")).toList();
                if (!generated.isEmpty()) {
                    var second = new DiagnosticCollector<JavaFileObject>();
                    try (StandardJavaFileManager fm = javac.getStandardFileManager(second, null, StandardCharsets.UTF_8)) {
                        fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                        Result result = compile(javac, fm, second, javacOutput, "compilation of the generated sources",
                                List.of("-proc:none", "--release", Integer.toString(release), "-Xlint:none"),
                                null, fm.getJavaFileObjectsFromPaths(generated), null);
                        if (!result.ok()) {
                            String first = second.getDiagnostics().stream()
                                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                                    .map(DependencyCodegen::message)
                                    .findFirst()
                                    .orElse(result.thrown() != null ? "javac threw " + result.thrown() : "javac failed");
                            lines.add(problem.line(allBricks, "generated code that does not compile", first));
                        }
                    }
                }
            }
            Files.createDirectories(log.toAbsolutePath().getParent());
            Files.writeString(log, javacOutput, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                throw new Failure(lines);
            }
            Map<String, byte[]> files = new TreeMap<>();
            for (Path root : List.of(res, classes)) {
                for (Path file : files(root)) {
                    files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            output = new Output(files, warnings);
        } catch (Throwable e) {
            // Whatever ends the run, an Error included, the temporary directory goes; its deletion must not hide
            // why the run failed.
            try {
                delete(tmp);
            } catch (IOException | RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        delete(tmp);
        return output;
    }

    /** How one javac task ended: whether it succeeded, and what a processor (or javac) threw, if anything. */
    private record Result(boolean ok, Throwable thrown) {
    }

    /**
     * Runs one javac task and appends its whole output to {@code log}.
     *
     * @param processors the processors to run, or {@code null} for none
     */
    private static Result compile(JavaCompiler javac, StandardJavaFileManager fm,
                                  DiagnosticCollector<JavaFileObject> diagnostics, StringBuilder log, String phase,
                                  List<String> options, List<String> classNames,
                                  Iterable<? extends JavaFileObject> sources, List<Processor> processors) {
        var out = new StringWriter();
        Result result;
        try {
            JavaCompiler.CompilationTask task = javac.getTask(out, fm, diagnostics, options, classNames, sources);
            if (processors != null) {
                task.setProcessors(processors);
            }
            result = new Result(task.call(), null);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            out.append("threw ").append(cause.toString()).append('\n');
            result = new Result(false, cause);
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
        return result;
    }

    private static String message(Diagnostic<?> d) {
        return firstLine(d.getMessage(Locale.ROOT));
    }

    private static String firstLine(String message) {
        int eol = message.indexOf('\n');
        return (eol < 0 ? message : message.substring(0, eol)).strip();
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /**
     * A temporary directory only its owner can enter. NIO already creates it so on a POSIX file system; the
     * permissions are spelled out so the shared temporary directory is visibly used safely.
     */
    private static Path ownerOnlyTempDirectory(String prefix) throws IOException {
        FileAttribute<?>[] ownerOnly = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}
                : new FileAttribute<?>[0];
        return Files.createTempDirectory(prefix, ownerOnly);
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Words the failure and warning lines of one jar: one line each, whatever the message (spec §6). */
    private record Problem(Path jar, String coordinates, String logDisplay) {
        String line(String bricks, String what, String message) {
            return "vidocq:generate: " + jar.getFileName() + " — " + bricks + " " + what + ": "
                    + firstLine(message)
                    + ". Fix the library, or leave the jar out with <scanExcludes>" + coordinates
                    + "</scanExcludes>. Full javac output: " + logDisplay;
        }

        String warning(String brick, String what, String message) {
            return "vidocq:generate: " + jar.getFileName() + " — " + brick + " warns about " + what + ": "
                    + firstLine(message);
        }
    }
}
