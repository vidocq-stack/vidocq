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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-mode hot reload by <b>application-layer re-creation</b>, in the same JVM: when the
 * host (the {@code vidocq:dev} mojo) signals a reload — it touches the file named by
 * {@value #RELOAD_FILE_PROPERTY} after a successful recompile — the running runtime is
 * shut down, the application {@link ModuleLayer} and its Vauban class loader are
 * discarded, and a fresh layer over the recompiled archives boots. No process respawn:
 * the JIT stays warm, dev services and debugger sessions survive, and the old layer's
 * classes become collectable once the new container drops the last reference.
 *
 * <p>Requires the universal-loader mode ({@code -Dvidocq.app.path=…}): the application
 * classes must live in the re-creatable layer, not on the JVM module path (those classes
 * are loaded once and for all). {@code -Dvidocq.app.main} is intentionally ignored here —
 * a user main would have to be re-entered on every cycle; the runtime boots directly.
 *
 * <p>A normal shutdown (Ctrl+C / SIGTERM → the {@code vidocq-shutdown} hook) still wins:
 * the loop polls the bootstrap's shutdown latch between signal checks and exits cleanly.
 */
final class VidocqDevReloadLoop {

    /** Path of the file the host touches to request a reload. */
    static final String RELOAD_FILE_PROPERTY = "vidocq.dev.reload.file";

    private static final System.Logger LOG = System.getLogger(VidocqDevReloadLoop.class.getName());
    private static final long POLL_MILLIS = 200;

    private VidocqDevReloadLoop() {}

    static void run(Path reloadFile, String[] args) {
        if (!System.getProperty(VidocqAppLayer.APP_MAIN_PROPERTY, "").isBlank()) {
            LOG.log(System.Logger.Level.INFO,
                    "Hot-reload mode: ignoring " + VidocqAppLayer.APP_MAIN_PROPERTY
                            + " (the runtime boots directly on every cycle)");
        }
        long lastSignal = signalStamp(reloadFile);
        while (true) {
            VidocqAppLayer.installIfConfigured();
            VidocqBootstrap bootstrap = VidocqBootstrap.create()
                    .configure()
                    .start();

            boolean reloadRequested = false;
            while (true) {
                if (bootstrap.awaitShutdown(POLL_MILLIS)) {
                    break; // normal shutdown (hook, or programmatic)
                }
                long stamp = signalStamp(reloadFile);
                if (stamp != lastSignal) {
                    lastSignal = stamp;
                    reloadRequested = true;
                    break;
                }
            }
            if (!reloadRequested) {
                return;
            }

            long t0 = System.nanoTime();
            LOG.log(System.Logger.Level.INFO,
                    "Hot reload - restarting on a fresh application layer");
            bootstrap.shutdown();
            VidocqAppLayer.resetForReload();
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            LOG.log(System.Logger.Level.INFO,
                    "Hot reload - previous deployment stopped in " + elapsed + " ms");
        }
    }

    private static long signalStamp(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException absentOrUnreadable) {
            return 0L;
        }
    }
}
