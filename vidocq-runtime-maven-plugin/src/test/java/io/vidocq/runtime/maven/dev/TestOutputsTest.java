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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BUG-20261117-01: who builds the main output, and what a run that was stopped leaves in the outputs it builds. */
class TestOutputsTest {

    /** When the run that was stopped started: a whole second, so that any file system keeps the times below. */
    private static final Instant STARTED = Instant.parse("2026-11-17T00:13:50Z");

    private static final TestOutputs.Output MAIN =
            new TestOutputs.Output(Path.of("target/classes"), List.of(Path.of("src/main/java")));
    private static final TestOutputs.Output TEST =
            new TestOutputs.Output(Path.of("target/test-classes"), List.of(Path.of("src/test/java")));

    @Test
    void underVidocqTestARunBuildsBothOutputs() {
        TestOutputs outputs = TestOutputs.ofVidocqTest(MAIN, TEST);

        assertEquals(List.of(MAIN, TEST), outputs.built());
        assertEquals(List.of(), outputs.skips(), "the run is the only build: it compiles the main sources too");
    }

    @Test
    void underVidocqDevARunBuildsTheTestOutputAndLeavesTheMainOneToTheRecompile() {
        TestOutputs outputs = TestOutputs.ofVidocqDev(TEST);

        assertEquals(List.of(TEST), outputs.built());
        assertEquals(List.of("-Dmaven.main.skip=true", "-Dmaven.resources.skip=true", "-Dvidocq.generate.skip=true"),
                outputs.skips(), "no main source compiled, no main resource copied, nothing generated");
    }

    /** A file of {@code content} last modified at {@code time}. */
    private static Path file(Path file, Instant time) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        Files.setLastModifiedTime(file, FileTime.from(time));
        return file;
    }

    /** An output whose sources do not matter to the test. */
    private static TestOutputs.Output output(Path directory) {
        return new TestOutputs.Output(directory, List.of());
    }

    /**
     * BUG-20261117-03: a test source saved while a run compiled it, after javac read it. The run ended before the dev
     * loop heard of the save, so nothing stopped it and its class stays, newer than the source. The next run deletes
     * that class, and says so as a line of the tests.
     */
    @Test
    void theNextRunCompilesTheTestSourceSavedWhileARunCompiledIt(@TempDir Path dir) throws Exception {
        Path testSources = dir.resolve("src/test/java");
        Path testClasses = dir.resolve("target/test-classes");
        Path source = file(testSources.resolve("com/acme/TallyTest.java"), STARTED.minusSeconds(2));
        Path itsClass = file(testClasses.resolve("com/acme/TallyTest.class"), STARTED.minusSeconds(60));
        List<String> info = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<SavedWhileCompiling> saved =
                TestOutputs.ofVidocqDev(new TestOutputs.Output(testClasses, List.of(testSources)))
                        .savedWhileCompiling(info::add, warnings::add);
        saved.forEach(SavedWhileCompiling::compileStarts);
        Files.setLastModifiedTime(source, FileTime.from(STARTED.plusSeconds(1)));
        Files.setLastModifiedTime(itsClass, FileTime.from(STARTED.plusSeconds(2)));
        saved.forEach(SavedWhileCompiling::compileEnds);

        saved.forEach(SavedWhileCompiling::compileStarts);

        assertFalse(Files.exists(itsClass), "the next run compiles a source that has no class");
        assertEquals(List.of("Tests: TallyTest.java was saved since the previous run started and its class is not"
                + " older: compiling it again."), info);
        assertEquals(List.of(), warnings);
    }

    /**
     * A run of {@code vidocq:test} compiles the main sources too: one saved while it compiled is compiled by the next
     * run. Under {@code vidocq:dev} the same class is the recompile's, and a run leaves it alone.
     */
    @Test
    void underVidocqTestTheMainSourcesAreTheRunsToo(@TempDir Path dir) throws Exception {
        TestOutputs.Output main =
                new TestOutputs.Output(dir.resolve("target/classes"), List.of(dir.resolve("src/main/java")));
        TestOutputs.Output test =
                new TestOutputs.Output(dir.resolve("target/test-classes"), List.of(dir.resolve("src/test/java")));
        Path source = file(dir.resolve("src/main/java/com/acme/Tally.java"), STARTED.minusSeconds(2));
        Path itsClass = file(dir.resolve("target/classes/com/acme/Tally.class"), STARTED.minusSeconds(60));
        List<String> info = new ArrayList<>();
        List<SavedWhileCompiling> ofVidocqDev = TestOutputs.ofVidocqDev(test).savedWhileCompiling(info::add, info::add);
        List<SavedWhileCompiling> ofVidocqTest =
                TestOutputs.ofVidocqTest(main, test).savedWhileCompiling(info::add, info::add);
        ofVidocqDev.forEach(SavedWhileCompiling::compileStarts);
        ofVidocqTest.forEach(SavedWhileCompiling::compileStarts);
        Files.setLastModifiedTime(source, FileTime.from(STARTED.plusSeconds(1)));
        Files.setLastModifiedTime(itsClass, FileTime.from(STARTED.plusSeconds(2)));
        ofVidocqDev.forEach(SavedWhileCompiling::compileEnds);
        ofVidocqTest.forEach(SavedWhileCompiling::compileEnds);

        ofVidocqDev.forEach(SavedWhileCompiling::compileStarts);
        assertTrue(Files.exists(itsClass), "under vidocq:dev, a class of the main output");

        ofVidocqTest.forEach(SavedWhileCompiling::compileStarts);
        assertFalse(Files.exists(itsClass), "under vidocq:test, the next run compiles it");
        assertEquals(List.of("Tests: Tally.java was saved since the previous run started and its class is not older:"
                + " compiling it again."), info);
    }

    /**
     * The run that was stopped compiled the main sources, copied a resource and started on the tests. Everything it
     * wrote goes, so that the next run finds its sources without a class and compiles them all; what an earlier
     * build wrote stays, and so does a file outside the outputs.
     */
    @Test
    void aStoppedRunLosesWhatItWroteInTheOutputsAndNothingElse(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("target/classes");
        Path testClasses = dir.resolve("target/test-classes");
        Path earlier = file(classes.resolve("com/acme/Earlier.class"), STARTED.minusSeconds(60));
        Path compiled = file(classes.resolve("com/acme/Tally.class"), STARTED.plusSeconds(1));
        Path copied = file(classes.resolve("vidocq.properties"), STARTED);
        Path test = file(testClasses.resolve("com/acme/TallyTest.class"), STARTED.plusSeconds(2));
        Path log = file(dir.resolve("target/vidocq-dev-tests.log"), STARTED.plusSeconds(2));

        TestOutputs.ofVidocqTest(output(classes), output(testClasses)).discard(STARTED);

        assertFalse(Files.exists(compiled), "a class the run compiled");
        assertFalse(Files.exists(copied), "a resource the run copied");
        assertFalse(Files.exists(test), "a test class the run compiled");
        assertTrue(Files.exists(earlier), "a class of an earlier build");
        assertTrue(Files.exists(log), "a file outside the outputs");
    }

    /** Under {@code vidocq:dev} the main output is the recompile's: nothing of it goes, however recent. */
    @Test
    void underVidocqDevOnlyTheTestOutputLosesAnything(@TempDir Path dir) throws Exception {
        Path recompiled = file(dir.resolve("target/classes/com/acme/Tally.class"), STARTED.plusSeconds(1));
        Path testClasses = dir.resolve("target/test-classes");
        Path test = file(testClasses.resolve("com/acme/TallyTest.class"), STARTED.plusSeconds(2));

        TestOutputs.ofVidocqDev(output(testClasses)).discard(STARTED);

        assertTrue(Files.exists(recompiled), "a class the recompile wrote while the run ran");
        assertFalse(Files.exists(test), "a test class the run compiled");
    }

    /**
     * A file system may keep the time of a file to the second, or to two: a file the run wrote just after it started
     * then bears a time before the start. Two seconds before the start still count as the run's.
     */
    @Test
    void aFileStampedUpToTwoSecondsBeforeTheStartIsTheRunsToo(@TempDir Path dir) throws Exception {
        Path testClasses = dir.resolve("target/test-classes");
        Path coarse = file(testClasses.resolve("com/acme/Coarse.class"), STARTED.minusSeconds(2));
        Path earlier = file(testClasses.resolve("com/acme/Earlier.class"), STARTED.minusSeconds(3));

        TestOutputs.ofVidocqDev(output(testClasses)).discard(STARTED);

        assertFalse(Files.exists(coarse), "stamped two seconds before the start");
        assertTrue(Files.exists(earlier), "stamped three seconds before the start");
    }

    /** A run stopped before it built anything, on a project that was never built: there is nothing to delete. */
    @Test
    void anOutputThatWasNeverBuiltIsNoError(@TempDir Path dir) {
        TestOutputs outputs = TestOutputs.ofVidocqTest(output(dir.resolve("target/classes")),
                output(dir.resolve("target/test-classes")));

        assertDoesNotThrow(() -> outputs.discard(STARTED));
    }
}
