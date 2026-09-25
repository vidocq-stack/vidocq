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

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VidocqTestMojoTest {

    private static final Function<String, Optional<String>> NO_FILES = key -> Optional.empty();
    private static final Function<String, Optional<String>> OFF_IN_FILES =
            key -> key.equals("vidocq.dev.devServices") ? Optional.of("false") : Optional.empty();

    @Test
    void devServicesAreOnByDefaultOffInTheFilesAndTheExplicitValueWins() throws Exception {
        VidocqTestMojo mojo = new VidocqTestMojo();

        assertTrue(mojo.devServicesEnabled(NO_FILES));
        assertFalse(mojo.devServicesEnabled(OFF_IN_FILES));
        mojo.setDevServices(true);
        assertTrue(mojo.devServicesEnabled(OFF_IN_FILES));
    }

    @Test
    void closingNoSessionIsHarmless() {
        assertDoesNotThrow(() -> new VidocqTestMojo().closeDevServices(null));
    }

    /**
     * #138: Ctrl+C runs the shutdown hook while execute() reaches its finally. The second caller must not return, and
     * go on to stop the dev services, while the first is still killing the test process.
     */
    @Test
    @Timeout(20)
    void aSecondShutdownWaitsForTheFirstToFinishKillingTheTests(@TempDir Path dir) throws Exception {
        AtomicBoolean killed = new AtomicBoolean();
        CountDownLatch running = new CountDownLatch(1);
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        ContinuousTesting testing = new ContinuousTesting(tests -> {
            running.countDown();
            return new ContinuousTesting.Launched() {
                @Override
                public int waitFor() throws InterruptedException {
                    try {
                        return exit.get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public void cancel() {
                    try {
                        Thread.sleep(500); // a slow kill
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    killed.set(true);
                    exit.complete(143);
                }

                @Override
                public boolean cancelled() {
                    return true;
                }
            };
        }, since -> SurefireReports.Reports.NONE, () -> false, dir.resolve("r.json"), "log", result -> {},
                warning -> {}, Clock.systemUTC());
        testing.start();
        testing.changed(TestResults.Trigger.RUN_ALL, TestControl.ReadyGate.NOW);
        running.await();
        VidocqTestMojo mojo = new VidocqTestMojo();

        Thread hook = Thread.ofPlatform().start(() -> mojo.shutdown(testing, null));
        Thread.sleep(100);
        mojo.shutdown(testing, null);

        assertTrue(killed.get(), "the finally returned before the hook finished killing the test process");
        hook.join();
    }
}
