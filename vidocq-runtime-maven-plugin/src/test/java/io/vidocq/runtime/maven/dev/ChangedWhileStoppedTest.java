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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * BUG-20261117-07: what changed while {@code vidocq:dev} did not run is built before the application starts. The
 * Maven of these tests is a script that notes each of its runs in {@code runs.txt}.
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(60)
class ChangedWhileStoppedTest {

    /** When the compiler of the last build listed its sources: a whole second, which any file system keeps. */
    private static final Instant COMPILED = Instant.parse("2026-11-17T06:10:07Z");

    /** When the Maven of this {@code vidocq:dev} started, long after that build. */
    private static final Instant STARTED = COMPILED.plusSeconds(3600);

    private static final String BUILDING = ": building the project first (mvn process-classes).";

    @TempDir
    Path dir;

    private Path java;
    private Path resources;
    private Path classes;
    private final List<String> info = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final AtomicInteger changes = new AtomicInteger();
    private ChangedWhileStopped stopped;

    /**
     * What the last build left: the record of the compiler, written before javac read {@code Menu.java}, then the
     * class, then the stamp of {@code vidocq:generate}.
     */
    @BeforeEach
    void aProjectTheLastBuildLeft() throws IOException {
        java = dir.resolve("src/main/java");
        resources = dir.resolve("src/main/resources");
        classes = dir.resolve("target/classes");
        Path menu = file(java.resolve("acme/Menu.java"), COMPILED.minusSeconds(5));
        theLastCompileRead(menu.toString());
        file(classes.resolve("acme/Menu.class"), COMPILED.plusSeconds(2));
        file(classes.resolve("META-INF/vidocq/generate.stamp"), COMPILED.plusSeconds(3));
        stopped = stopped(dir.resolve("target"), classes, FileTime.from(STARTED));
    }

    private ChangedWhileStopped stopped(Path buildDir, Path output, FileTime started) {
        return new ChangedWhileStopped(List.of(java, resources), List.of(java), output, buildDir, started,
                info::add, warnings::add);
    }

    private static Path file(Path file, Instant time) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        Files.setLastModifiedTime(file, FileTime.from(time));
        return file;
    }

    /** A Maven that runs {@code script} in the project directory. */
    private CompilerOutput.Recompile maven(String script) throws IOException {
        Path maven = dir.resolve("fake-mvn");
        Files.writeString(maven, "#!/bin/sh\n" + script + "\n");
        assertTrue(maven.toFile().setExecutable(true));
        return new RecompileRunner(dir, maven.toString())::run;
    }

    private List<String> runs() throws IOException {
        Path runs = dir.resolve("runs.txt");
        return Files.exists(runs) ? Files.readAllLines(runs) : List.of();
    }

    /** The next change of the sources: one more each time, never the end of the watch. */
    private boolean aChange() {
        changes.incrementAndGet();
        return true;
    }

    /** What the compiler plugin notes of the sources it is given, one path a line, when the last build compiled. */
    private void theLastCompileRead(String... sources) throws IOException {
        Path inputs = dir.resolve("target/maven-status/maven-compiler-plugin/compile/default-compile/inputFiles.lst");
        Files.createDirectories(inputs.getParent());
        Files.write(inputs, List.of(sources));
        Files.setLastModifiedTime(inputs, FileTime.from(COMPILED));
    }

    /**
     * The start after a build: nothing is later than the record of the compiler, and not a word. A file of the very
     * millisecond of that record is not later.
     */
    @Test
    void nothingChangedSinceTheLastBuildBuildsNothing() throws Exception {
        file(resources.resolve("vidocq.properties"), COMPILED);

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of(), runs());
        assertEquals(List.of(), info);
        assertEquals(List.of(), warnings);
    }

    /** The loss: a source edited while {@code vidocq:dev} did not run. Nothing compiles it under a bare goal. */
    @Test
    void aSourceEditedSinceTheLastBuildIsBuiltFirst() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, Menu.java changed" + BUILDING), info);
        assertEquals(List.of(), warnings);
        assertEquals(0, changes.get(), "it built: no change to wait for");
    }

    /**
     * An IDE compiled the source it saved, into the same output: the class is newer than the source, and yet no
     * build of Maven saw that source, so the generated code is the one of the last build. The record of the compiler
     * tells, where the times of the output would not.
     */
    @Test
    void aSourceAnIdeCompiledSinceTheLastBuildIsBuiltFirst() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));
        file(classes.resolve("acme/Menu.class"), COMPILED.plusSeconds(61));

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, Menu.java changed" + BUILDING), info);
    }

    /** A resource is copied by the build as a source is compiled: a new one counts as an edited one. */
    @Test
    void aResourceAddedSinceTheLastBuildIsBuiltFirst() throws Exception {
        file(resources.resolve("META-INF/menu.properties"), COMPILED.plusSeconds(1));

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, menu.properties changed" + BUILDING), info);
    }

    /** Several changes, one build: their number, and the first three names, in the order of the paths. */
    @Test
    void severalChangesAreOneBuildThatNamesThree() throws Exception {
        for (String name : List.of("Tally", "Menu", "Receipt", "Basket")) {
            file(java.resolve("acme/" + name + ".java"), COMPILED.plusSeconds(60));
        }

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, 4 files changed (Basket.java, Menu.java, Receipt.java, …)"
                + BUILDING), info);
    }

    /** Only what the dev loop watches counts: a file it would not compile on a save starts no build either. */
    @Test
    void aFileTheDevLoopDoesNotWatchStartsNoBuild() throws Exception {
        file(java.resolve("acme/notes.txt"), COMPILED.plusSeconds(60));
        file(dir.resolve("src/test/java/acme/MenuTest.java"), COMPILED.plusSeconds(60));

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of(), runs());
        assertEquals(List.of(), info);
    }

    /** The last second before Maven started is still a time {@code vidocq:dev} did not run. */
    @Test
    void aFileSavedJustBeforeMavenStartedIsBuiltFirst() throws Exception {
        file(java.resolve("acme/Menu.java"), STARTED.minusSeconds(1));

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, Menu.java changed" + BUILDING), info);
    }

    /**
     * A file saved since Maven started was saved while {@code vidocq:dev} started: the start compiles it before the
     * application starts (BUG-20261117-05), and names it once. No build here.
     */
    @Test
    void aFileSavedSinceMavenStartedIsLeftToTheStart() throws Exception {
        file(java.resolve("acme/Menu.java"), STARTED);
        file(resources.resolve("vidocq.properties"), STARTED.plusSeconds(2));

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of(), runs());
        assertEquals(List.of(), info);
        assertEquals(List.of(), warnings);
    }

    /** A goal run without a Maven session has no start to tell from: every file later than the build counts. */
    @Test
    void withoutAMavenSessionEveryLaterFileCounts() throws Exception {
        file(java.resolve("acme/Menu.java"), STARTED.plusSeconds(2));

        stopped(dir.resolve("target"), classes, null).build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, Menu.java changed" + BUILDING), info);
    }

    /**
     * A source deleted while {@code vidocq:dev} did not run: no file is newer, and its class is still in the output.
     * The compiler plugin notes the sources it is given: one of them that is gone is a change.
     */
    @Test
    void aSourceTheLastBuildCompiledAndThatIsGoneIsBuiltFirst() throws Exception {
        theLastCompileRead(java.resolve("acme/Menu.java").toString(), java.resolve("acme/Tally.java").toString());

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, Tally.java is gone" + BUILDING), info);
        assertEquals(List.of(), warnings);
    }

    /** Both kinds of change are one line, and one build. */
    @Test
    void anEditedSourceAndSourcesThatAreGoneAreOneLine() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));
        theLastCompileRead(java.resolve("acme/Menu.java").toString(), java.resolve("acme/Tally.java").toString(),
                java.resolve("acme/Basket.java").toString());

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, Menu.java changed and 2 sources are gone (Basket.java,"
                + " Tally.java)" + BUILDING), info);
    }

    /**
     * The compiler plugin also notes the sources the annotation processors wrote, under the build directory: the
     * build deletes and writes them itself. Only a source of the project's own roots counts, and a line that is not
     * a path is skipped.
     */
    @Test
    void aGeneratedSourceThatIsGoneIsNotAChangeOfTheProject() throws Exception {
        theLastCompileRead(java.resolve("acme/Menu.java").toString(),
                dir.resolve("target/generated-sources/annotations/acme/Menu_Index.java").toString(), "",
                "not\0a path");

        stopped.build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of(), runs());
        assertEquals(List.of(), info);
        assertEquals(List.of(), warnings);
    }

    /** A build that fails says so, then waits for the next change and builds again, as a first build does. */
    @Test
    void aBuildThatFailsWaitsForTheNextChangeAndBuildsAgain() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));

        stopped.build(maven("echo run >> runs.txt\nif [ ! -e failed-once ]; then touch failed-once; exit 1; fi"),
                this::aChange);

        assertEquals(List.of("run", "run"), runs());
        assertEquals(List.of("The project does not build (exit 1): fix the sources, vidocq dev builds again on the"
                + " next change."), warnings);
        assertEquals(1, changes.get());
    }

    /** No change will come once the watch ended: there is no application to start on sources that do not build. */
    @Test
    void theWatchThatEndsWhileTheBuildFailsStopsTheStart() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));
        CompilerOutput.Recompile failing = maven("echo run >> runs.txt\nexit 1");

        MojoExecutionException stop = assertThrows(MojoExecutionException.class,
                () -> stopped.build(failing, () -> false));

        assertEquals("vidocq dev stopped before the project compiled.", stop.getMessage());
        assertEquals(List.of("run"), runs());
    }

    /** Ctrl+C while it waits: the start stops, and the thread stays interrupted for its caller. */
    @Test
    void anInterruptWhileItWaitsStopsTheStart() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));
        CompilerOutput.Recompile failing = maven("exit 1");
        CompilerOutput.AwaitChange interrupted = () -> {
            throw new InterruptedException("Ctrl+C");
        };

        MojoExecutionException stop = assertThrows(MojoExecutionException.class,
                () -> stopped.build(failing, interrupted));

        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertEquals("Interrupted while building " + classes, stop.getMessage());
    }

    /** A Maven that cannot be run builds nothing: a warning, and the application starts as it did before. */
    @Test
    void aMavenThatCannotRunLeavesTheClassesOfTheLastBuild() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));
        CompilerOutput.Recompile absent = new RecompileRunner(dir, dir.resolve("no-such-mvn").toString())::run;

        stopped.build(absent, this::aChange);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Cannot build the project ("), warnings.getFirst());
        assertTrue(warnings.getFirst().endsWith("): the application starts on the classes of the last build."),
                warnings.getFirst());
        assertEquals(0, changes.get());
    }

    /** A directory that cannot be read: a warning says what to do, and the start goes on without a build. */
    @Test
    void aDirectoryThatCannotBeReadIsAWarning() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root reads every directory");
        Path closed = file(java.resolve("acme/closed/Tally.java"), COMPILED.plusSeconds(60)).getParent();
        Files.setPosixFilePermissions(closed, Set.of());
        try {
            stopped.build(maven("echo run >> runs.txt"), this::aChange);
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
        }

        assertEquals(List.of(), runs());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Cannot tell whether the sources changed since the last build"
                + " (java.nio.file.AccessDeniedException"), warnings.getFirst());
        assertTrue(warnings.getFirst().endsWith("): if a change does not show, save the file again."),
                warnings.getFirst());
    }

    /**
     * A build that left no record of the compiler, as one whose pom turns the incremental compilation off: the
     * newest file of the output is when it ended. A file of that very millisecond is not newer.
     */
    @Test
    void withoutARecordOfTheCompilerNothingNewerThanTheOutputBuildsNothing() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(3));

        stopped(dir.resolve("no-record"), classes, FileTime.from(STARTED))
                .build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of(), runs());
        assertEquals(List.of(), info);
        assertEquals(List.of(), warnings);
    }

    /** Without that record, a file newer than everything the build left is a change. */
    @Test
    void withoutARecordOfTheCompilerAFileNewerThanTheOutputIsBuiltFirst() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(4));

        stopped(dir.resolve("no-record"), classes, FileTime.from(STARTED))
                .build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("Since the last build, Menu.java changed" + BUILDING), info);
    }

    /** An output that holds no file is one nobody built: the first build of the goal is another check's. */
    @Test
    void anOutputNobodyBuiltIsLeftToTheFirstBuild() throws Exception {
        file(java.resolve("acme/Menu.java"), COMPILED.plusSeconds(60));
        Path empty = Files.createDirectories(dir.resolve("target/empty"));

        stopped(dir.resolve("no-record"), empty, FileTime.from(STARTED))
                .build(maven("echo run >> runs.txt"), this::aChange);
        stopped(dir.resolve("no-record"), dir.resolve("target/absent"), FileTime.from(STARTED))
                .build(maven("echo run >> runs.txt"), this::aChange);

        assertEquals(List.of(), runs());
        assertEquals(List.of(), info);
        assertEquals(List.of(), warnings);
    }
}
