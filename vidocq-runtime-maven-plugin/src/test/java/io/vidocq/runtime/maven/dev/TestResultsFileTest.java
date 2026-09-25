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

import io.vidocq.runtime.maven.dev.TestResults.Counts;
import io.vidocq.runtime.maven.dev.TestResults.Failure;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResultsFileTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:12:03.456Z");
    private static final String LOG = "target/vidocq-dev-tests.log";

    private static TestResults failed() {
        return TestResults.completed(Trigger.CHANGE, AT, 3210, 1, new SurefireReports.Reports(
                new Counts(42, 1, 0, 0), List.of(new Failure("com.acme.OrderServiceTest#rejectsEmptyCart",
                        "org.opentest4j.AssertionFailedError", "expected: <400> but was: <200>")), 1, 0),
                LOG, warning -> {});
    }

    @Test
    void theDocumentHasTheSpecsShape() {
        assertEquals("{\"state\":\"failed\",\"trigger\":\"change\",\"startedAt\":\"2026-09-25T10:12:03Z\","
                + "\"durationMillis\":3210,\"counts\":{\"run\":42,\"failures\":1,\"errors\":0,\"skipped\":0},"
                + "\"failures\":[{\"test\":\"com.acme.OrderServiceTest#rejectsEmptyCart\","
                + "\"type\":\"org.opentest4j.AssertionFailedError\",\"message\":\"expected: <400> but was: <200>\"}],"
                + "\"log\":\"target/vidocq-dev-tests.log\"}", TestResultsFile.json(failed()));
    }

    @Test
    void runningNestsThePreviousResultOnce() {
        String json = TestResultsFile.json(TestResults.running(Trigger.TEST_CHANGE, AT, LOG, failed()));

        assertTrue(json.startsWith("{\"state\":\"running\",\"trigger\":\"test-change\""), json);
        assertTrue(json.contains(",\"previous\":{\"state\":\"failed\""), json);
        assertEquals(json.indexOf("\"previous\""), json.lastIndexOf("\"previous\""), json);
    }

    @Test
    void stringsAreEscaped() {
        TestResults odd = TestResults.completed(Trigger.CHANGE, AT, 1, 1, new SurefireReports.Reports(
                new Counts(1, 1, 0, 0), List.of(new Failure("a.B#c", "T", "say \"hi\" \\ \t\u0001")), 1, 0),
                LOG, warning -> {});

        assertTrue(TestResultsFile.json(odd).contains("\"message\":\"say \\\"hi\\\" \\\\ \\t\\u0001\""),
                TestResultsFile.json(odd));
    }

    @Test
    void writingReplacesTheFileAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestResultsFile.FILE_NAME);
        TestResultsFile.write(file, TestResults.running(Trigger.RUN_ALL, AT, LOG, null));
        TestResultsFile.write(file, failed());

        assertEquals(TestResultsFile.json(failed()), Files.readString(file));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(TestResultsFile.FILE_NAME), files.map(p -> p.getFileName().toString()).toList());
        }
    }
}
