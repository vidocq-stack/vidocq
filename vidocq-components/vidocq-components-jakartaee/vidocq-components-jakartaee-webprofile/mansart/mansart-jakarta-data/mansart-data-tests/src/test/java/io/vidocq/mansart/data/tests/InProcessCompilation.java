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
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.processor.MansartProcessor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Runs {@link MansartProcessor} inside the test JVM: javac compiles the given sources with the processor, against
 * this module's test class path minus its own output directories, into {@code out/classes} and
 * {@code out/generated}.
 */
final class InProcessCompilation {

    private static final String JAVA = ".java";
    /** The outcome of one compilation. */
    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path classes,
                  Path generated) {

        List<String> errors() {
            return messages(Diagnostic.Kind.ERROR);
        }

        List<String> warnings() {
            List<String> out = new ArrayList<>(messages(Diagnostic.Kind.WARNING));
            out.addAll(messages(Diagnostic.Kind.MANDATORY_WARNING));
            return out;
        }

        private List<String> messages(Diagnostic.Kind kind) {
            return diagnostics.stream().filter(d -> d.getKind() == kind)
                    .map(d -> d.getMessage(Locale.ROOT)).toList();
        }

        /** The text of a generated source, by fully qualified type name. */
        String generatedSource(String fqn) {
            try {
                return Files.readString(generated.resolve(fqn.replace('.', '/') + JAVA), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private InProcessCompilation() {
    }

    /** Compiles every {@code .java} file under {@code sourceRoot}. */
    static Result compileTree(Path sourceRoot, Path out) throws IOException {
        return compileTree(sourceRoot, out, List.of());
    }

    /** Compiles every {@code .java} file under {@code sourceRoot}, with extra javac {@code options}. */
    static Result compileTree(Path sourceRoot, Path out, List<String> options) throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            sources = walk.filter(p -> p.toString().endsWith(JAVA)).sorted().toList();
        }
        return compile(sources, out, options);
    }

    /** Writes each {@code fqn -> source} pair under {@code out/src} and compiles them. */
    static Result compileSources(Path out, String... fqnAndSourcePairs) throws IOException {
        return compileSources(out, List.of(), fqnAndSourcePairs);
    }

    /** Writes each {@code fqn -> source} pair under {@code out/src} and compiles them with extra {@code options}. */
    static Result compileSources(Path out, List<String> options, String... fqnAndSourcePairs) throws IOException {
        return compileSources(out, options, List.of(), fqnAndSourcePairs);
    }

    /**
     * Writes each {@code fqn -> source} pair under {@code out/src} and compiles them, with extra javac
     * {@code options} and {@code extraClassPath} (a library jar the application depends on) after the test class path.
     */
    static Result compileSources(Path out, List<String> options, List<Path> extraClassPath,
                                 String... fqnAndSourcePairs) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < fqnAndSourcePairs.length; i += 2) {
            Path file = out.resolve("src").resolve(fqnAndSourcePairs[i].replace('.', '/') + JAVA);
            Files.createDirectories(file.getParent());
            Files.writeString(file, fqnAndSourcePairs[i + 1], StandardCharsets.UTF_8);
            sources.add(file);
        }
        return compile(sources, out, options, extraClassPath);
    }

    private static Result compile(List<Path> sources, Path out, List<String> options) throws IOException {
        return compile(sources, out, options, List.of());
    }

    private static Result compile(List<Path> sources, Path out, List<String> options, List<Path> extraClassPath)
            throws IOException {
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
            List<Path> classPath = new ArrayList<>(dependencyClassPath());
            classPath.addAll(extraClassPath);
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics,
                    Stream.concat(Stream.of("-parameters", "-proc:full", "-Xlint:none"), options.stream()).toList(),
                    null,
                    files.getJavaFileObjectsFromPaths(sources));
            task.setProcessors(List.of(new MansartProcessor()));
            boolean success = task.call();
            return new Result(success, List.copyOf(diagnostics.getDiagnostics()), classes, generated);
        }
    }

    /**
     * Compiles a library the way a third party would, with no annotation processing: each {@code fqn -> source}
     * pair under {@code out/src}, into {@code out/classes}, with or without {@code -parameters}.
     */
    static Result compileLibrary(Path out, boolean parameterNames, String... fqnAndSourcePairs) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < fqnAndSourcePairs.length; i += 2) {
            Path file = out.resolve("src").resolve(fqnAndSourcePairs[i].replace('.', '/') + JAVA);
            Files.createDirectories(file.getParent());
            Files.writeString(file, fqnAndSourcePairs[i + 1], StandardCharsets.UTF_8);
            sources.add(file);
        }
        Path classes = Files.createDirectories(out.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, dependencyClassPath());
            List<String> options = new ArrayList<>(List.of("-proc:none", "-Xlint:none"));
            if (parameterNames) {
                options.add("-parameters");
            }
            boolean success = javac.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjectsFromPaths(sources)).call();
            return new Result(success, List.copyOf(diagnostics.getDiagnostics()), classes, out.resolve("generated"));
        }
    }

    /**
     * Runs Mansart's processor over compiled classes, as {@code vidocq:generate} does on a dependency jar:
     * {@code javac -proc:only} given the binary names, the library's classes on the class path. The generated
     * sources go to {@code out/generated}, the resources to {@code out/classes}.
     */
    static Result processClassFiles(Path libraryClasses, Path out, String... binaryNames) throws IOException {
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
            List<Path> classPath = new ArrayList<>(dependencyClassPath());
            classPath.add(libraryClasses);
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics,
                    List.of("-proc:only", "-implicit:none", "-Xlint:none"), List.of(binaryNames), null);
            task.setProcessors(List.of(new MansartProcessor()));
            boolean success = task.call();
            return new Result(success, List.copyOf(diagnostics.getDiagnostics()), classes, generated);
        }
    }

    /**
     * Compiles the sources a {@link #processClassFiles} run generated against the library's classes, as
     * {@code vidocq:generate} does before it writes them into the enriched copy.
     */
    static Result compileGenerated(Result processed, Path libraryClasses, Path out) throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(processed.generated())) {
            sources = walk.filter(p -> p.toString().endsWith(JAVA)).sorted().toList();
        }
        Path classes = Files.createDirectories(out.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            List<Path> classPath = new ArrayList<>(dependencyClassPath());
            classPath.add(libraryClasses);
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            boolean success = javac.getTask(null, files, diagnostics, List.of("-proc:none", "-Xlint:none"), null,
                    files.getJavaFileObjectsFromPaths(sources)).call();
            return new Result(success, List.copyOf(diagnostics.getDiagnostics()), classes, processed.generated());
        }
    }

    /** The test class path without this module's {@code target/classes} and {@code target/test-classes}. */
    private static List<Path> dependencyClassPath() {
        Path target = Path.of("target").toAbsolutePath().normalize();
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().normalize())
                .filter(p -> !p.startsWith(target))
                .toList();
    }
}
