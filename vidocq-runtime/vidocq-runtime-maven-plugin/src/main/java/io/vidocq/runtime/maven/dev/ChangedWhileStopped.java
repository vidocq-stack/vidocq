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

import io.vidocq.runtime.maven.CompilerOutput;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The changes made while {@code vidocq:dev} did not run, which no build of Maven saw (BUG-20261117-07).
 *
 * <p>A bare {@code mvn vidocq:dev} runs no phase of the lifecycle: the goal starts the application from the output
 * the last build left. A file edited, added or deleted since is not in that output, and the watcher, which is not
 * there yet, never reports it. The application would start without the change, and stay so until another save.
 *
 * <p>Two signs tell such a change without running Maven. Both come from the record the compiler plugin leaves of
 * its last run, the list of the sources it was given, which it writes each time it runs, compiling or not, just
 * before javac reads them:
 * <ul>
 *   <li>a watched file saved after that list was written, and before the Maven of this goal started. The times of
 *       the output would not tell: an IDE that compiles into the same directory writes classes newer than the
 *       sources it saved, and no build of Maven ran on them;</li>
 *   <li>a source of that list that is gone: no file is newer, and its class is still in the output.</li>
 * </ul>
 *
 * <p>A file saved since Maven started is not one of them: it was saved while {@code vidocq:dev} started, and
 * {@link SavedWhileStarting} compiles it, so that a save is built and named once. A build that left no such list, as
 * one whose pom turns the incremental compilation off, is compared by the newest file of its output.
 *
 * <p>{@link #build} then builds the project, before anything of the start reads the output. A command line that ran
 * the lifecycle first, as {@code vidocq dev} does, wrote the list in this very Maven: nothing is to build.
 *
 * <p>That build would leave out a changed source whose class is not older, the compiler plugin taking it for
 * compiled: the class javac wrote from what it read before the save, or the one an IDE compiled. Such a class is
 * deleted before the build runs (BUG-20261117-08).
 */
final class ChangedWhileStopped {

    private final List<Path> watch;
    private final List<Path> sourceRoots;
    private final Path output;
    private final Path buildDir;
    private final FileTime started;
    private final Consumer<String> info;
    private final Consumer<String> warn;

    /**
     * @param watch       the directories the session watches for the application
     * @param sourceRoots the project's own source roots, the generated ones left out
     * @param output      where the build writes the classes and copies the resources
     * @param buildDir    the build directory, where the compiler keeps its record
     * @param started     when the Maven of this goal started; {@code null} without a Maven session
     * @param info        gets what changed, by name, three files at most of each kind
     * @param warn        gets what could not be told, or does not build
     */
    ChangedWhileStopped(List<Path> watch, List<Path> sourceRoots, Path output, Path buildDir, FileTime started,
            Consumer<String> info, Consumer<String> warn) {
        this.watch = List.copyOf(watch);
        this.sourceRoots = List.copyOf(sourceRoots);
        this.output = output;
        this.buildDir = buildDir;
        this.started = started;
        this.info = info;
        this.warn = warn;
    }

    /**
     * To call when the goal starts, once the output holds a build. Builds the project when something changed since
     * that build. While the build fails, waits for the next change and builds again, as a first build that fails
     * does: there is no application to start on sources that do not build. A directory that cannot be read, or a
     * Maven that cannot be run, is a warning, and the start goes on with the classes of the last build. One case
     * stops the start instead: a Maven that cannot be run once the class of a source in doubt was deleted
     * (BUG-20261117-08), the output being incomplete from then on.
     *
     * @param build  builds the project, and returns the exit code of its Maven
     * @param change blocks until the sources change; {@code false} once the watch ended
     * @throws MojoExecutionException when the watch ended, or the thread was interrupted, before the project built,
     *                                and when Maven cannot be run to compile a source whose class was deleted
     */
    void build(CompilerOutput.Recompile build, CompilerOutput.AwaitChange change) throws MojoExecutionException {
        Optional<CompilerOutput.LastCompile> compiled;
        try {
            compiled = CompilerOutput.lastCompile(buildDir);
            long built = compiled.isPresent() ? compiled.get().listed().toMillis() : newestOf(output);
            if (!changedSince(built, compiled.map(last -> last.gone(sourceRoots)).orElse(List.of()))) {
                return;
            }
        } catch (IOException e) {
            warn.accept("Cannot tell whether the sources changed since the last build (" + e
                    + "): if a change does not show, save the file again.");
            return;
        }
        List<Path> dropped = compiled.map(this::dropTheClassesInDoubt).orElse(List.of());
        try {
            int exit = build.run();
            while (exit != 0) {
                warn.accept("The project does not build (exit " + exit + "): fix the sources, vidocq dev builds again"
                        + " on the next change.");
                if (!change.await()) {
                    throw new MojoExecutionException("vidocq dev stopped before the project compiled.");
                }
                exit = build.run();
            }
        } catch (IOException e) {
            if (!dropped.isEmpty()) {
                throw incomplete(e, dropped);
            }
            warn.accept("Cannot build the project (" + e.getMessage()
                    + "): the application starts on the classes of the last build.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while building " + output, e);
        }
    }

    /**
     * Whether something changed since the last build; says what.
     *
     * @param built when that build compiled, in milliseconds; negative when nobody built
     * @param gone  the sources it compiled that no longer exist
     */
    private boolean changedSince(long built, List<Path> gone) throws IOException {
        if (built < 0) {
            return false; // nobody built: the first build of the goal is another check's
        }
        List<Path> edited = SourceWatcher.savedBetween(watch, FileTime.fromMillis(built + 1), started);
        if (edited.isEmpty() && gone.isEmpty()) {
            return false;
        }
        info.accept("Since the last build, " + named(edited, gone)
                + ": building the project first (mvn process-classes).");
        return true;
    }

    /**
     * Deletes the class of each source saved since the compiler of the last build listed its sources, when that
     * class is not older than the source: the compiler plugin would take the source for compiled, in the build of
     * this start too (BUG-20261117-08). Such a class is one javac wrote from what it read before the save, the
     * build being still at work, or one an IDE compiled without the annotation processors. Without it, the plugin
     * compiles the module. {@link SavedWhileCompiling} tells and names them, as it does inside a session.
     *
     * @return the sources whose class it deleted, in the order of their paths
     */
    private List<Path> dropTheClassesInDoubt(CompilerOutput.LastCompile last) {
        SavedWhileCompiling missed = new SavedWhileCompiling(sourceRoots, output, "build", info, warn);
        missed.anEarlierCompileStarted("the last build", last.listed());
        return missed.compileStarts();
    }

    /**
     * The end of a start whose Maven cannot be run once classes were deleted to be compiled again: the output is
     * not the one of the last build any more, and nothing can complete it.
     */
    private static MojoExecutionException incomplete(IOException cause, List<Path> dropped) {
        String classes = dropped.size() == 1 ? "The class of " + SavedWhileCompiling.names(dropped) + " was"
                : "The classes of " + SavedWhileCompiling.names(dropped) + " were";
        return new MojoExecutionException("Cannot build the project (" + cause.getMessage() + "). " + classes
                + " deleted to be compiled again, so the output is incomplete: run \"mvn process-classes\", then"
                + " vidocq:dev again.", cause);
    }

    /** What changed, each kind with its number and the names of its first files. */
    private static String named(List<Path> edited, List<Path> gone) {
        List<String> kinds = new ArrayList<>();
        if (edited.size() == 1) {
            kinds.add(edited.getFirst().getFileName() + " changed");
        } else if (!edited.isEmpty()) {
            kinds.add(edited.size() + " files changed (" + SavedWhileCompiling.names(edited) + ")");
        }
        if (gone.size() == 1) {
            kinds.add(gone.getFirst().getFileName() + " is gone");
        } else if (!gone.isEmpty()) {
            kinds.add(gone.size() + " sources are gone (" + SavedWhileCompiling.names(gone) + ")");
        }
        return String.join(" and ", kinds);
    }

    /** The time of the newest file under {@code directory}, in milliseconds; {@code -1} when it holds no file. */
    private static long newestOf(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return -1;
        }
        Newest newest = new Newest();
        Files.walkFileTree(directory, newest);
        return newest.millis;
    }

    /** Keeps the latest time of the files it visits. */
    private static final class Newest extends SimpleFileVisitor<Path> {

        private long millis = -1;

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            millis = Math.max(millis, attributes.lastModifiedTime().toMillis());
            return FileVisitResult.CONTINUE;
        }
    }
}
