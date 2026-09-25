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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * When a hot reload completed (spec §2.2): after each boot the child writes the stamp of the signal it answered to
 * {@code <reload file>.ready} ({@code VidocqDevReloadLoop.acknowledge} in the core). The tests of a main change wait
 * for that stamp, so they never overlap the reload. A reload that never answers — a boot that failed, a child that
 * died — never blocks the tests: they run after the timeout, or at once when the child is gone, with a warning.
 */
final class ReloadAck {

    /** The acknowledgement file's suffix; the core writes the same name. */
    static final String SUFFIX = ".ready";
    /** How often the acknowledgement is looked for. */
    static final long POLL_MILLIS = 100;

    private ReloadAck() {}

    static Path readyFile(Path reloadFile) {
        return reloadFile.resolveSibling(reloadFile.getFileName() + SUFFIX);
    }

    /** The stamp the child sees for the signal just written: the reload file's modification time. */
    static long stamp(Path reloadFile) throws IOException {
        return Files.getLastModifiedTime(reloadFile).toMillis();
    }

    static boolean acknowledged(Path reloadFile, long stamp) {
        try {
            return Files.readString(readyFile(reloadFile), StandardCharsets.UTF_8).strip()
                    .equals(Long.toString(stamp));
        } catch (IOException notYet) {
            return false;
        }
    }

    /** The gate of the tests that follow the signal {@code stamp}. */
    static TestControl.ReadyGate gate(Path reloadFile, long stamp, Duration timeout, BooleanSupplier childAlive,
            Consumer<String> warn) {
        return abandoned -> {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (abandoned.getAsBoolean()) {
                    return false;
                }
                if (acknowledged(reloadFile, stamp)) {
                    return true;
                }
                if (!childAlive.getAsBoolean()) {
                    warn.accept("Tests: the application stopped before its reload completed; running the tests"
                            + " anyway");
                    return true;
                }
                Thread.sleep(POLL_MILLIS);
            }
            warn.accept("Tests: the reload was not confirmed within " + timeout.toSeconds()
                    + " s; running the tests anyway");
            return true;
        };
    }
}
