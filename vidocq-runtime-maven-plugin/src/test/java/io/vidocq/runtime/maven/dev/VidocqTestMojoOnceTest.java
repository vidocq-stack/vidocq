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
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code vidocq test --once}: the goal runs every test once and ends with the verdict, instead of watching. */
@Timeout(20)
class VidocqTestMojoOnceTest {

    /** A test process that has already ended with {@code code}. */
    private record Ended(int code) implements ContinuousTesting.Launched {
        @Override public int waitFor() { return code; }
        @Override public void cancel() {
            // the process has already ended
        }
        @Override public boolean cancelled() { return false; }
    }

    private static void runOnce(Path dir, int exit, SurefireReports.Reports reports) throws Exception {
        CompletableFuture<TestResults> verdict = new CompletableFuture<>();
        ContinuousTesting testing = new ContinuousTesting(tests -> new Ended(exit), since -> reports, () -> true,
                new ContinuousTesting.Results(dir.resolve(TestResultsFile.FILE_NAME), "log"),
                result -> {
                    if (result.state().complete()) {
                        verdict.complete(result);
                    }
                }, warning -> {
                    // no warning expected in these runs
                }, Clock.systemUTC());
        new VidocqTestMojo().runOnce(testing, null, new Thread(() -> {
            // a hook that is never registered
        }), verdict);
    }

    @Test
    void passingTestsEndTheGoalSuccessfully(@TempDir Path dir) {
        assertDoesNotThrow(() -> runOnce(dir, 0, new SurefireReports.Reports(new Counts(3, 0, 0, 0), List.of(), 1, 0)));
    }

    @Test
    void aFailingTestFailsTheGoal(@TempDir Path dir) {
        var failures = List.of(new Failure("BookTest#loads", "org.opentest4j.AssertionFailedError", "no"));
        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> runOnce(dir, 1, new SurefireReports.Reports(new Counts(3, 1, 0, 0), failures, 1, 0)));
        assertEquals("1 of 3 test(s) failed", failure.getMessage());
    }
}
