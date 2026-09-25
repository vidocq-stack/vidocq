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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Sanity coverage of {@link SourceWatcher}. The macOS {@code WatchService}
 * uses polling (~10 s latency) so timeouts are generous and we use a sub-dir
 * with at least one pre-existing file to avoid the first-event missed bug.
 */
class SourceWatcherTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void awaitChange_detects_a_relevant_file_modification(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("Hello.java");
        Files.writeString(file, "class Hello {}");

        try (SourceWatcher watcher = SourceWatcher.on(List.of(tmp), Duration.ofMillis(50))) {
            AtomicBoolean changed = new AtomicBoolean();
            CountDownLatch threadStarted = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                threadStarted.countDown();
                try {
                    changed.set(watcher.awaitChange());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            threadStarted.await();

            // Give the watcher a moment to register before we touch the file —
            // otherwise on slow CI the modification may land before the key
            // is armed.
            Thread.sleep(500);
            Files.writeString(file, "class Hello { /* edit */ }");

            t.join(TimeUnit.SECONDS.toMillis(15));
            assertFalse(t.isAlive(), "watcher should have returned by now");
            assertTrue(changed.get(), "awaitChange should report a change");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void awaitChange_ignores_files_with_unwatched_extensions(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("ignore.log"), "noise");

        try (SourceWatcher watcher = SourceWatcher.on(List.of(tmp), Duration.ofMillis(50))) {
            AtomicInteger result = new AtomicInteger(-1);
            Thread t = new Thread(() -> {
                try {
                    result.set(watcher.awaitChange() ? 1 : 0);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            Thread.sleep(500);
            // .log is NOT in the watched set — this must not wake up awaitChange.
            Files.writeString(tmp.resolve("ignore.log"), "noise updated");

            t.join(2000);
            // Either the thread is still parked (good — we ignored the event),
            // or the watcher fired due to some other system event we cannot
            // control on macOS; in either case .log alone must never wake it.
            if (!t.isAlive()) {
                // If it did wake up, the only legitimate reason would be a directory
                // event that happened to carry an unrelated .java file — which
                // does not happen in this tmp dir. So the result must be 0.
                assertEquals(0, result.get(),
                        "watcher woke up but reported a change for .log, which is filtered out");
            }
            t.interrupt();
            t.join(1000);
        }
    }

    @Test
    void aFileUnderATestRootIsATestChange(@TempDir Path tmp) {
        Path testRoot = tmp.resolve("src/test/java").toAbsolutePath().normalize();

        assertTrue(SourceWatcher.under(tmp.resolve("src/test/java/a/BTest.java"), List.of(testRoot)));
        assertFalse(SourceWatcher.under(tmp.resolve("src/main/java/a/B.java"), List.of(testRoot)));
        assertFalse(SourceWatcher.under(tmp.resolve("src/test/javax/C.java"), List.of(testRoot)));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void awaitChanges_tells_a_test_change_from_a_main_one(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("src/main/java"));
        Path test = Files.createDirectories(tmp.resolve("src/test/java"));
        Path testFile = test.resolve("HelloTest.java");
        Files.writeString(main.resolve("Hello.java"), "class Hello {}");
        Files.writeString(testFile, "class HelloTest {}");

        try (SourceWatcher watcher = SourceWatcher.on(List.of(main), List.of(test), Duration.ofMillis(50))) {
            AtomicReference<SourceWatcher.Change> change = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    change.set(watcher.awaitChanges());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            Thread.sleep(500);
            Files.writeString(testFile, "class HelloTest { /* edit */ }");

            t.join(TimeUnit.SECONDS.toMillis(15));
            assertFalse(t.isAlive(), "watcher should have returned by now");
            assertEquals(new SourceWatcher.Change(false, true), change.get());
        }
    }
}
