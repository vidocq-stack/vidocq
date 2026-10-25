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

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Checks, before {@code vidocq:dev} or {@code vidocq:run} launches, that {@code target/classes} still holds every
 * file the Maven compiler created, annotation processor output included (Vidocq/vidocq#237).
 *
 * <p>An IDE build that compiles the same output directory without the annotation processors, as IntelliJ's does
 * when it cannot read a {@code -codegen} bundle, leaves only the hand-written classes. {@code maven-compiler-plugin}
 * then finds them up to date and never runs the processors again: the application starts without its generated
 * beans, web components or services. The compiler's own record, {@code createdFiles.lst} under
 * {@code target/maven-status}, names what it wrote, so a gap is detected without knowing any processor.
 */
public final class CompilerOutput {

    /** Where {@code maven-compiler-plugin} keeps one directory of incremental state per compile execution. */
    private static final String STATE = "maven-status/maven-compiler-plugin/compile";

    /** How many missing files a message names. */
    private static final int SHOWN = 3;

    /** Recompiles the project, returning the exit code of the Maven run; {@code 0} is a success. */
    @FunctionalInterface
    public interface Recompile {
        int run() throws IOException, InterruptedException;
    }

    private CompilerOutput() {
    }

    /**
     * The files the compiler executions of this build recorded as created and that {@code classes} no longer
     * holds, relative to it, sorted. Empty when nothing is missing or when the compiler recorded nothing.
     */
    static List<String> missing(Path buildDir, Path classes) throws IOException {
        Path state = buildDir.resolve(STATE);
        if (!Files.isDirectory(state)) {
            return List.of();
        }
        TreeSet<String> missing = new TreeSet<>();
        try (Stream<Path> executions = Files.list(state)) {
            for (Path execution : executions.sorted().toList()) {
                Path created = execution.resolve("createdFiles.lst");
                if (!Files.isRegularFile(created)) {
                    continue;
                }
                for (String line : Files.readAllLines(created)) {
                    String file = line.strip();
                    if (!file.isEmpty() && !Files.exists(classes.resolve(file))) {
                        missing.add(file);
                    }
                }
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Builds the project before {@code vidocq:dev} launches when nobody did: {@code target/classes} is missing or
     * holds no file, as right after {@code mvn clean}. {@link #ensureComplete} cannot see it, as it only compares
     * with what the compiler recorded, and there is no record yet. One {@code mvn process-classes} then does what
     * every reload does, the executions bound to its phases included, such as Vaadin's {@code build-frontend} and
     * {@code vidocq:generate}. A project that has nothing to compile is launched all the same after that build.
     *
     * @throws MojoExecutionException when the build fails
     */
    public static void ensureBuilt(Path classes, Recompile build, Consumer<String> warn)
            throws MojoExecutionException {
        if (hasFiles(classes)) {
            return;
        }
        warn.accept("nothing in " + classes + ": building the project first (mvn process-classes).");
        int exit;
        try {
            exit = build.run();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot build " + classes + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while building " + classes, e);
        }
        if (exit != 0) {
            throw new MojoExecutionException("The first build of " + classes
                    + " (mvn process-classes) failed (exit " + exit + ").");
        }
    }

    private static boolean hasFiles(Path classes) {
        if (!Files.isDirectory(classes)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(classes)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException | UncheckedIOException _) {   // Files.walk reports an unreadable directory unchecked
            return true;   // unreadable: do not start a build on a guess, the launch will report it
        }
    }

    /**
     * Makes the output complete before a launch: when files the compiler created are missing, warns, deletes the
     * compiler's incremental state, which makes its next run a full compile with the processors, and recompiles once.
     *
     * @throws MojoExecutionException when the recompile fails or the output is still incomplete after it
     */
    public static void ensureComplete(Path buildDir, Path classes, Recompile recompile, Consumer<String> warn)
            throws MojoExecutionException {
        List<String> missing = read(buildDir, classes);
        if (missing.isEmpty()) {
            return;
        }
        warn.accept(missing.size() + " file(s) the compiler created are missing from " + classes + " ("
                + shown(missing) + "): another build, usually the IDE's, compiled it without the annotation"
                + " processors, and Maven sees nothing to recompile. Resetting the compiler state and recompiling.");
        int exit;
        try {
            forgetIncrementalState(buildDir);
            exit = recompile.run();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot recompile " + classes + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while recompiling " + classes, e);
        }
        if (exit != 0) {
            throw new MojoExecutionException("The recompile of " + classes + " failed (exit " + exit + ").");
        }
        List<String> still = read(buildDir, classes);
        if (!still.isEmpty()) {
            throw new MojoExecutionException(still.size() + " file(s) the compiler created are still missing from "
                    + classes + " after a full recompile (" + shown(still) + "). Run \"mvn clean\" and build again.");
        }
    }

    private static List<String> read(Path buildDir, Path classes) throws MojoExecutionException {
        try {
            return missing(buildDir, classes);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read the compiler state under " + buildDir.resolve(STATE)
                    + ": " + e.getMessage(), e);
        }
    }

    private static void forgetIncrementalState(Path buildDir) throws IOException {
        Path state = buildDir.resolve(STATE);
        if (!Files.exists(state)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(state)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private static String shown(List<String> files) {
        String names = String.join(", ", files.subList(0, Math.min(SHOWN, files.size())));
        return files.size() > SHOWN ? names + ", …" : names;
    }
}
