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
package io.vidocq.runtime.maven.dev;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * The output directories the runs of the tests build, and what a run that was stopped leaves in them
 * (BUG-20261117-01).
 *
 * <p>The compiler plugin takes a source for compiled when its class is newer. A Maven that is stopped while javac
 * writes its classes leaves an output that looks whole: the classes of the hand-written sources are there and newer
 * than their sources, the ones javac writes last, of the sources an annotation processor generated, are not. The next
 * build compiles nothing, and fails on a class that is missing, until a source changes. A change stops the run in
 * flight, so this happens by design, in two ways:
 *
 * <ul>
 *   <li>Under {@code vidocq:dev} the recompile of the dev loop builds the main output. A run started just after a
 *       save compiled the main sources itself, the change stopped it, and the recompile then failed. A run now gets
 *       {@link #skips}: it compiles no main source, copies no main resource and generates nothing.</li>
 *   <li>A run that was stopped may have half-built an output it does build: {@link #discard} deletes what it wrote,
 *       so that the next run finds sources without a class and compiles them all.</li>
 * </ul>
 *
 * <p>A run that ends before the dev loop hears of a save is not stopped, and may have compiled that source from what
 * javac read before the save: {@link #savedWhileCompiling} gives each output what tells the next run so
 * (BUG-20261117-03).
 *
 * @param built the outputs a run builds
 * @param skips the switches of a run that keep it away from an output it does not build
 */
record TestOutputs(List<Output> built, List<String> skips) {

    /**
     * An output a run builds.
     *
     * @param directory   where the compiler plugin writes the classes
     * @param sourceRoots the source roots it compiles there that are outside the build directory: the sources a
     *                    developer saves
     */
    record Output(Path directory, List<Path> sourceRoots) {

        Output {
            sourceRoots = List.copyOf(sourceRoots);
        }
    }

    /**
     * How coarse the time of a file may be: a file system that keeps it to the second, or to two, gives a file
     * written just after a run started a time before that start.
     */
    static final Duration COARSEST_FILE_TIME = Duration.ofSeconds(2);

    /** What starts every line about the tests. */
    private static final String TESTS = "Tests: ";

    /** {@code vidocq:test}: a run is the only build of the project, it builds the main output and the test one. */
    static TestOutputs ofVidocqTest(Output main, Output test) {
        return new TestOutputs(List.of(main, test), List.of());
    }

    /** {@code vidocq:dev}: the recompile of the dev loop builds the main output, a run builds the test one. */
    static TestOutputs ofVidocqDev(Output test) {
        return new TestOutputs(List.of(test),
                List.of("-Dmaven.main.skip=true", "-Dmaven.resources.skip=true", "-Dvidocq.generate.skip=true"));
    }

    /**
     * Deletes the files of {@link #built} that the run started at {@code since} wrote, once it was stopped: the ones
     * modified since then, {@link #COARSEST_FILE_TIME} earlier included. A file of an earlier build that this deletes
     * too is built again, as the others; a directory that does not exist has nothing to delete.
     */
    void discard(Instant since) throws IOException {
        FileTime from = FileTime.from(since.minus(COARSEST_FILE_TIME));
        for (Output output : built) {
            if (Files.isDirectory(output.directory())) {
                Files.walkFileTree(output.directory(), new DeleteModifiedFrom(from));
            }
        }
    }

    /**
     * What tells a run the saves the previous run missed, one for each output of {@link #built}: a source saved while
     * that run compiled it, after javac read it, has a class that looks up to date, and the run that starts deletes
     * it (BUG-20261117-03). What they say are lines of the tests.
     */
    List<SavedWhileCompiling> savedWhileCompiling(Consumer<String> info, Consumer<String> warn) {
        return built.stream()
                .map(output -> new SavedWhileCompiling(output.sourceRoots(), output.directory(), "run",
                        line -> info.accept(TESTS + line), line -> warn.accept(TESTS + line)))
                .toList();
    }

    /** Deletes the regular files it visits, at any depth, that were last modified at {@code from} or later. */
    private static final class DeleteModifiedFrom extends SimpleFileVisitor<Path> {

        private final FileTime from;

        DeleteModifiedFrom(FileTime from) {
            this.from = from;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (attributes.isRegularFile() && attributes.lastModifiedTime().compareTo(from) >= 0) {
                Files.deleteIfExists(file);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
