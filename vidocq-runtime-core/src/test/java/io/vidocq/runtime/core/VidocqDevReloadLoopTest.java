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
package io.vidocq.runtime.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VidocqDevReloadLoopTest {

    @Test
    void theReadyFileIsTheReloadFileWithReady(@TempDir Path dir) {
        assertEquals(dir.resolve(".vidocq-dev-reload.ready"),
                VidocqDevReloadLoop.readyFile(dir.resolve(".vidocq-dev-reload")));
    }

    @Test
    void acknowledgingWritesTheStampAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        Path reload = dir.resolve(".vidocq-dev-reload");

        VidocqDevReloadLoop.acknowledge(reload, 1234L);
        VidocqDevReloadLoop.acknowledge(reload, 5678L);

        assertEquals("5678\n", Files.readString(dir.resolve(".vidocq-dev-reload.ready")));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(".vidocq-dev-reload.ready"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFailureNeverStopsTheApplication(@TempDir Path dir) {
        assertDoesNotThrow(() -> VidocqDevReloadLoop.acknowledge(dir.resolve("absent/.vidocq-dev-reload"), 1L));
    }
}
