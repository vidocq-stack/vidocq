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

import io.vidocq.runtime.maven.dev.SavedWhileStarting.Outcome;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * BUG-20261117-05: what is saved while {@code vidocq:dev} starts is compiled before the application starts. The Maven
 * of these tests is a script that notes each of its runs in {@code runs.txt}.
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(60)
class SavedWhileStartingTest {

    /** When Maven started: a whole second, so that any file system keeps the times below. */
    private static final Instant STARTED = Instant.parse("2026-11-17T04:23:24Z");

    @TempDir
    Path dir;

    private Path java;
    private Path resources;
    private Path classes;
    private final List<String> info = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final AtomicInteger changes = new AtomicInteger();
    private SavedWhileCompiling compiling;
    private SavedWhileStarting starting;

    @BeforeEach
    void aProjectMavenBuilt() {
        java = dir.resolve("src/main/java");
        resources = dir.resolve("src/main/resources");
        classes = dir.resolve("target/classes");
        compiling = new SavedWhileCompiling(List.of(java), classes, "recompile", info::add, warnings::add);
        starting = new SavedWhileStarting(List.of(java, resources), FileTime.from(STARTED), info::add, warnings::add);
    }

    private static Path file(Path file, Instant time) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        Files.setLastModifiedTime(file, FileTime.from(time));
        return file;
    }

    /** A Maven that runs {@code script} in the project directory. */
    private RecompileRunner maven(String script) throws IOException {
        Path maven = dir.resolve("fake-mvn");
        Files.writeString(maven, "#!/bin/sh\n" + script + "\n");
        assertTrue(maven.toFile().setExecutable(true));
        return new RecompileRunner(dir, maven.toString());
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

    /** The start nobody saves during: no recompile, and not a word. */
    @Test
    void nothingSavedSinceMavenStartedCompilesNothing() throws Exception {
        file(java.resolve("acme/Menu.java"), STARTED.minusSeconds(5));
        file(resources.resolve("vidocq.properties"), STARTED.minusMillis(1));

        assertEquals(Outcome.NOTHING_COMPILED,
                starting.compile(compiling, maven("echo run >> runs.txt"), this::aChange));

        assertEquals(List.of(), runs());
        assertEquals(List.of(), info);
        assertEquals(List.of(), warnings);
    }

    /**
     * A file saved after the build copied it, before the watcher was there: nothing would compile it until another
     * save. One recompile, before the application starts.
     */
    @Test
    void aFileSavedSinceMavenStartedIsCompiledBeforeTheApplicationStarts() throws Exception {
        file(resources.resolve("vidocq.properties"), STARTED.plusSeconds(2));
        file(java.resolve("acme/Menu.java"), STARTED.minusSeconds(5));

        assertEquals(Outcome.COMPILED, starting.compile(compiling, maven("echo run >> runs.txt"), this::aChange));

        assertEquals(List.of("run"), runs());
        assertEquals(List.of("vidocq.properties was saved while vidocq:dev started: compiling it before the"
                + " application starts."), info);
        assertEquals(List.of(), warnings);
        assertEquals(0, changes.get(), "it compiled: no change to wait for");
    }

    /**
     * A source saved while the build compiled it, after javac read it: its class is newer, and the compiler plugin
     * takes it for compiled. The recompile finds no class, and compiles it.
     */
    @Test
    void aSourceTheBuildMayHaveReadBeforeItsSaveLosesItsClass() throws Exception {
        file(java.resolve("acme/Menu.java"), STARTED.plusSeconds(2));
        file(classes.resolve("acme/Menu.class"), STARTED.plusSeconds(3));

        assertEquals(Outcome.COMPILED, starting.compile(compiling, maven(
                "if [ -e target/classes/acme/Menu.class ]; then echo 'its class is there'; else echo 'no class'; fi"
                        + " >> runs.txt"), this::aChange));

        assertEquals(List.of("no class"), runs());
        assertEquals(List.of(
                "Menu.java was saved while vidocq:dev started: compiling it before the application starts.",
                "Menu.java was saved since the build started and its class is not older: compiling it again."), info);
    }

    /** Several saves are counted, and named three at most, in the order of their paths. */
    @Test
    void theSavesAreNamedThreeAtMost() throws Exception {
        for (String name : List.of("acme/Tally.java", "acme/Menu.java", "acme/Dish.java", "acme/Bill.java")) {
            file(java.resolve(name), STARTED.plusSeconds(2));
        }

        assertEquals(Outcome.COMPILED, starting.compile(compiling, maven("echo run >> runs.txt"), this::aChange));

        assertEquals(List.of("4 files were saved while vidocq:dev started (Bill.java, Dish.java, Menu.java, …):"
                + " compiling them before the application starts."), info);
    }

    /**
     * A save that does not compile: there is no application to keep up, so the session waits for the next change and
     * compiles again, as a first build that fails does.
     */
    @Test
    void savesThatDoNotCompileWaitForTheNextChange() throws Exception {
        file(java.resolve("acme/Menu.java"), STARTED.plusSeconds(2));
        RecompileRunner failsOnce = maven("echo run >> runs.txt\n"
                + "if [ ! -e failed-once ]; then touch failed-once; exit 1; fi");

        assertEquals(Outcome.COMPILED, starting.compile(compiling, failsOnce, this::aChange));

        assertEquals(List.of("run", "run"), runs());
        assertEquals(1, changes.get(), "one change, awaited after the compile that failed");
        assertEquals(List.of("What was saved while vidocq:dev started does not compile (exit 1): fix the sources,"
                + " vidocq dev compiles again on the next change."), warnings);
    }

    /** Ctrl+C, or a watch that ends, while the saves do not compile: the session ends without an application. */
    @Test
    void theWatchThatEndsBeforeTheSavesCompileEndsTheSession() throws Exception {
        file(java.resolve("acme/Menu.java"), STARTED.plusSeconds(2));

        assertEquals(Outcome.WATCH_ENDED,
                starting.compile(compiling, maven("echo run >> runs.txt\nexit 1"), () -> false));

        assertEquals(List.of("run"), runs());
    }

    /** A directory that cannot be read: a warning, no recompile, and the application starts. */
    @Test
    void aDirectoryThatCannotBeReadIsAWarning() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root reads every directory");
        Path closed = file(java.resolve("acme/closed/Menu.java"), STARTED.plusSeconds(2)).getParent();
        Files.setPosixFilePermissions(closed, Set.of());
        Outcome outcome;
        try {
            outcome = starting.compile(compiling, maven("echo run >> runs.txt"), this::aChange);
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
        }

        assertEquals(Outcome.NOTHING_COMPILED, outcome);
        assertEquals(List.of(), runs());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Cannot tell whether a file was saved while vidocq:dev started"
                + " (java.nio.file.AccessDeniedException"), warnings.getFirst());
        assertTrue(warnings.getFirst().endsWith("): if a save does not show, save the file again."),
                warnings.getFirst());
    }

    /** A Maven that cannot be run: a warning, and the application starts on the output as it is. */
    @Test
    void aMavenThatCannotRunIsAWarning() throws Exception {
        file(resources.resolve("vidocq.properties"), STARTED.plusSeconds(2));
        RecompileRunner noMaven = new RecompileRunner(dir, dir.resolve("no-such-mvn").toString());

        assertEquals(Outcome.NOTHING_COMPILED, starting.compile(compiling, noMaven, this::aChange));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Cannot compile what was saved while vidocq:dev started ("),
                warnings.getFirst());
        assertTrue(warnings.getFirst().endsWith("): the application starts without it. Save the file again once"
                + " it runs."), warnings.getFirst());
        assertFalse(Files.exists(dir.resolve("runs.txt")));
    }
}
