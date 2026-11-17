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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BUG-20261117-01: who builds the main output, and what a run that was stopped leaves in the outputs it builds. */
class TestOutputsTest {

    /** When the run that was stopped started: a whole second, so that any file system keeps the times below. */
    private static final Instant STARTED = Instant.parse("2026-11-17T00:13:50Z");

    private static final Path CLASSES = Path.of("target/classes");
    private static final Path TEST_CLASSES = Path.of("target/test-classes");

    @Test
    void underVidocqTestARunBuildsBothOutputs() {
        TestOutputs outputs = TestOutputs.ofVidocqTest(CLASSES, TEST_CLASSES);

        assertEquals(List.of(CLASSES, TEST_CLASSES), outputs.built());
        assertEquals(List.of(), outputs.skips(), "the run is the only build: it compiles the main sources too");
    }

    @Test
    void underVidocqDevARunBuildsTheTestOutputAndLeavesTheMainOneToTheRecompile() {
        TestOutputs outputs = TestOutputs.ofVidocqDev(TEST_CLASSES);

        assertEquals(List.of(TEST_CLASSES), outputs.built());
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

        TestOutputs.ofVidocqTest(classes, testClasses).discard(STARTED);

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

        TestOutputs.ofVidocqDev(testClasses).discard(STARTED);

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

        TestOutputs.ofVidocqDev(testClasses).discard(STARTED);

        assertFalse(Files.exists(coarse), "stamped two seconds before the start");
        assertTrue(Files.exists(earlier), "stamped three seconds before the start");
    }

    /** A run stopped before it built anything, on a project that was never built: there is nothing to delete. */
    @Test
    void anOutputThatWasNeverBuiltIsNoError(@TempDir Path dir) {
        TestOutputs outputs = TestOutputs.ofVidocqTest(dir.resolve("target/classes"), dir.resolve("target/test-classes"));

        assertDoesNotThrow(() -> outputs.discard(STARTED));
    }
}
