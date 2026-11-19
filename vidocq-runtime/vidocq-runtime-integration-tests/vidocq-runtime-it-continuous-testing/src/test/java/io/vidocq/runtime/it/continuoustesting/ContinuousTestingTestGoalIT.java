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
package io.vidocq.runtime.it.continuoustesting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@code mvn vidocq:test} on the fixture (spec §7): the summary of the first run, no key hints since
 * standard input is a pipe, and a fixed test gives {@code passed}.
 */
@DisabledOnOs(OS.WINDOWS)
class ContinuousTestingTestGoalIT {

    @Test
    void eachRunIsSummarisedAndAFixedTestTurnsItGreen(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Path logs = Fixture.logs();
        Fixture.warmUp(project, logs.resolve("test-warm-up.log"));
        Path log = logs.resolve("vidocq-test.log");

        Process mvn = Fixture.start(project, log, "vidocq:test");
        try {
            Fixture.awaitLog(mvn, log, "Tests: 2 passed, 1 failed, 0 skipped");
            String out = Fixture.read(log);
            assertTrue(out.contains("FAILED io.vidocq.it.ct.ExpectationTest#matchesTheGreeting"), out);
            assertTrue(out.contains("AssertionFailedError: expected: <Goodbye, Ada> but was: <Hello, Ada>"), out);
            assertFalse(out.contains("[r] run all"), "a pipe is no console: no keys");

            Fixture.fixExpectation(project);
            Fixture.awaitResults(project, r -> Fixture.is(r, "passed", "test-change"));
            Fixture.awaitLog(mvn, log, "Tests: 3 passed, 0 failed, 0 skipped");
        } finally {
            Fixture.stop(mvn);
        }
    }
}
