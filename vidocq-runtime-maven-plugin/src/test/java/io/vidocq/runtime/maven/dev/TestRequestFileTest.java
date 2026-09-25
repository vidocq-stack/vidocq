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

import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestRequestFileTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void aRequestIsReadOnceAndDeleted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestRequestFile.FILE_NAME);
        TestRequestFile requests = new TestRequestFile(file, warnings::add);
        Files.writeString(file, "rerun-failed\n");

        assertEquals(Optional.of(Trigger.RERUN_FAILED), requests.take());
        assertFalse(Files.exists(file));
        assertEquals(Optional.empty(), requests.take());
    }

    @Test
    void anUnknownRequestIsIgnoredAndLoggedOnce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestRequestFile.FILE_NAME);
        TestRequestFile requests = new TestRequestFile(file, warnings::add);

        Files.writeString(file, "com.acme.Evil#test");
        assertEquals(Optional.empty(), requests.take());
        Files.writeString(file, "change");
        assertEquals(Optional.empty(), requests.take());

        assertEquals(1, warnings.size(), warnings.toString());
        assertFalse(warnings.getFirst().contains("Evil"), "the content is never quoted: " + warnings);
    }

    @Test
    @Timeout(10)
    void pollingDeletesAStaleRequestThenDeliversNewOnes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestRequestFile.FILE_NAME);
        Files.writeString(file, "run-all");
        BlockingQueue<Trigger> seen = new LinkedBlockingQueue<>();

        try (TestRequestFile ignored = TestRequestFile.poll(file, seen::add, warnings::add)) {
            assertNull(seen.poll(600, TimeUnit.MILLISECONDS), "a request left by a killed session never runs");
            Files.writeString(file, "run-all");
            assertEquals(Trigger.RUN_ALL, seen.poll(5, TimeUnit.SECONDS));
        }
    }
}
