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

    private static final String CANNOT_PROCESS_JAR = "cannot process the jar";

    /**
     * The file that receives javac's full output of one jar, and that file as a failure line prints it.
     *
     * @param file    {@code target/vidocq-depgen/<artifactId>.log}
     * @param display {@code file} relative to the project's base directory
     */
    public record LogFile(Path file, String display) {}

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
     * @param log         where javac's full output goes, and how a failure line names it
     */
    public static Output run(Path jar, List<Brick> bricks, List<String> classNames, List<Path> classpath,
                             int release, String coordinates, LogFile log)
            throws Failure, IOException {
        if (bricks.isEmpty()) {
            throw new IllegalArgumentException("no brick for " + jar);
        }
        // Only the classes the hints point at: passing every class made javac fail on classes that need an optional
        // API absent from the build, such as atmosphere-runtime's websocket endpoints (spec §4.1).
        if (classNames.isEmpty()) {
            return new Output(Map.of(), List.of());
        }
        var problem = new Problem(jar, coordinates, log.display());
        String allBricks = bricks.stream().map(b -> b.hints().brick()).distinct().collect(Collectors.joining(", "));
        // Whatever ends the run, an Error included, the temporary directory goes; its deletion must not hide why the
        // run failed: try-with-resources adds it as suppressed.
        try (var tmp = new TempDirectory(ownerOnlyTempDirectory("vidocq-depgen-"))) {
            Path src = Files.createDirectories(tmp.path().resolve("src"));
            Path res = Files.createDirectories(tmp.path().resolve("res"));
            Path classes = Files.createDirectories(tmp.path().resolve("classes"));
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                throw new IllegalStateException("vidocq:generate needs a JDK: no system Java compiler");
            }
            var job = new Job(javac, problem, allBricks, classpath, Integer.toString(release));
            job.process(bricks, classNames, src, res);
            if (job.lines.isEmpty()) {
                job.compileGenerated(src, classes);
            }
            Files.createDirectories(log.file().toAbsolutePath().getParent());
            Files.writeString(log.file(), job.javacOutput, StandardCharsets.UTF_8);
            if (!job.lines.isEmpty()) {
                throw new Failure(job.lines);
            }
            return new Output(readFiles(res, classes), job.warnings);
        }
    }

    /** One jar's two javac passes, and the failure and warning lines they produce. */
    private static final class Job {
        private final JavaCompiler javac;
        private final Problem problem;
        private final String allBricks;
        private final List<Path> classpath;
        private final String release;
        private final StringBuilder javacOutput = new StringBuilder();
        private final List<String> lines = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        Job(JavaCompiler javac, Problem problem, String allBricks, List<Path> classpath, String release) {
            this.javac = javac;
            this.problem = problem;
            this.allBricks = allBricks;
            this.classpath = classpath;
            this.release = release;
        }

        /** Runs the bricks' processors over {@code classNames}, generating into {@code src} and {@code res}. */
        void process(List<Brick> bricks, List<String> classNames, Path src, Path res) throws IOException {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var processors = new AttributedProcessors(bricks);
                 StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(src));
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(res));
                Result result = processors.reports().isEmpty()
                        ? compile(fm, diagnostics, "annotation processing",
                                List.of("-proc:only", "-implicit:none", "--release", release, "-Xlint:none"),
                                classNames, null, processors.processors())
                        : new Result(false, null);
                List<String> reported = addReports(processors.reports());
                addUnreportedErrors(diagnostics, reported);
                if (result.thrown() != null) {
                    String brick = processors.thrownBy() != null ? processors.thrownBy() : allBricks;
                    lines.add(problem.line(brick, CANNOT_PROCESS_JAR, "a processor threw " + result.thrown()));
                }
                if (!result.ok() && lines.isEmpty()) {
                    lines.add(problem.line(allBricks, CANNOT_PROCESS_JAR, "javac failed"));
                }
            }
        }

        /**
         * Words what the processors reported, an error as a failure line and a warning as a warning line.
         *
         * @return the messages of the errors: javac shows each again as a diagnostic without a source
         */
        private List<String> addReports(List<AttributedProcessors.Report> reports) {
            List<String> reported = new ArrayList<>();
            for (AttributedProcessors.Report report : reports) {
                if (report.isError()) {
                    reported.add(report.message());
                    lines.add(problem.line(report.brick(), "cannot process " + report.what(),
                            firstLine(report.message())));
                } else {
                    warnings.add(problem.warning(report.brick(), report.what(), firstLine(report.message())));
                }
            }
            return reported;
        }

        /** Adds a failure line per javac error, skipping once each error a processor already reported. */
        private void addUnreportedErrors(DiagnosticCollector<JavaFileObject> diagnostics, List<String> reported) {
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR && !reported.remove(d.getMessage(Locale.ROOT))) {
                    lines.add(problem.line(allBricks, CANNOT_PROCESS_JAR, message(d)));
                }
            }
        }

        /** Compiles the sources the processors generated in {@code src} into {@code classes}, if any. */
        void compileGenerated(Path src, Path classes) throws IOException {
            List<Path> generated = files(src).stream().filter(p -> p.toString().endsWith(".java")).toList();
            if (generated.isEmpty()) {
                return;
            }
            var second = new DiagnosticCollector<JavaFileObject>();
            try (StandardJavaFileManager fm = javac.getStandardFileManager(second, null, StandardCharsets.UTF_8)) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                Result result = compile(fm, second, "compilation of the generated sources",
                        List.of("-proc:none", "--release", release, "-Xlint:none"),
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

        /**
         * Runs one javac task and appends its whole output to the javac output.
         *
         * @param processors the processors to run, or {@code null} for none
         */
        private Result compile(StandardJavaFileManager fm, DiagnosticCollector<JavaFileObject> diagnostics,
                               String phase, List<String> options, List<String> classNames,
                               Iterable<? extends JavaFileObject> sources, List<Processor> processors) {
            StringBuilder log = javacOutput;
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
    }

    /** The generated resources and class files, relative path → bytes. */
    private static Map<String, byte[]> readFiles(Path res, Path classes) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (Path root : List.of(res, classes)) {
            for (Path file : files(root)) {
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    /** A temporary directory deleted on close. */
    private record TempDirectory(Path path) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            delete(path);
        }
    }

    /** How one javac task ended: whether it succeeded, and what a processor (or javac) threw, if anything. */
    private record Result(boolean ok, Throwable thrown) {
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
