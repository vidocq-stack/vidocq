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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class ReloadAckTest {

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Test
    void theReadyFileIsTheReloadFilesSibling() {
        assertEquals(Path.of("target/.vidocq-dev-reload.ready"),
                ReloadAck.readyFile(Path.of("target/.vidocq-dev-reload")));
    }

    @Test
    void theGateOpensWhenTheBootAnswersThisStamp(@TempDir Path dir) throws Exception {
        Path reload = dir.resolve(".vidocq-dev-reload");
        Files.writeString(ReloadAck.readyFile(reload), "41\n");
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(300);
                Files.writeString(ReloadAck.readyFile(reload), "42\n");
            } catch (Exception ignored) {
                // the test fails on its timeout
            }
        });

        assertTrue(ReloadAck.gate(reload, 42, Duration.ofSeconds(5), () -> true, warnings::add)
                .await(() -> false));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aNewerChangeAbandonsTheWait(@TempDir Path dir) throws Exception {
        assertFalse(ReloadAck.gate(dir.resolve(".vidocq-dev-reload"), 42, Duration.ofSeconds(5), () -> true,
                warnings::add).await(() -> true));
    }

    @Test
    void noAnswerInTimeRunsTheTestsAnywayWithAWarning(@TempDir Path dir) throws Exception {
        assertTrue(ReloadAck.gate(dir.resolve(".vidocq-dev-reload"), 42, Duration.ofMillis(300), () -> true,
                warnings::add).await(() -> false));
        assertEquals(1, warnings.size());
    }

    @Test
    void aChildThatDiedRunsTheTestsAnywayWithAWarning(@TempDir Path dir) throws Exception {
        assertTrue(ReloadAck.gate(dir.resolve(".vidocq-dev-reload"), 42, Duration.ofSeconds(5), () -> false,
                warnings::add).await(() -> false));
        assertTrue(warnings.getFirst().contains("stopped"), warnings.toString());
    }
}
