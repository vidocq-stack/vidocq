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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Sanity coverage of {@link SourceWatcher}. The macOS {@code WatchService}
 * uses polling (~10 s latency) so timeouts are generous and we use a sub-dir
 * with at least one pre-existing file to avoid the first-event missed bug.
 */
class SourceWatcherTest {

    private static final String BURST_MAIN = "burst/main";
    private static final String BURST_TEST = "burst/test";

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
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            threadStarted.await();

            // Wait for the watcher to block on its keys before we touch the file —
            // otherwise on slow CI the modification may land before it waits.
            awaitBlocked(t);
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
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            awaitBlocked(t);
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
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            awaitBlocked(t);
            Files.writeString(testFile, "class HelloTest { /* edit */ }");

            t.join(TimeUnit.SECONDS.toMillis(15));
            assertFalse(t.isAlive(), "watcher should have returned by now");
            assertEquals(new SourceWatcher.Change(false, true), change.get());
        }
    }

    /** #138: a test directory created after the start, such as a first src/test/java, is watched from then on. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void a_test_directory_created_later_is_watched(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(main.resolve("Hello.java"), "class Hello {}");
        Path test = tmp.resolve("src/test/java");

        try (SourceWatcher watcher = SourceWatcher.on(List.of(main), List.of(test), Duration.ofMillis(50))) {
            AtomicReference<SourceWatcher.Change> change = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    change.set(watcher.awaitChanges());
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            awaitBlocked(t);
            Files.createDirectories(test.resolve("a"));
            Files.writeString(test.resolve("a/HelloTest.java"), "class HelloTest {}");

            t.join(TimeUnit.SECONDS.toMillis(20));
            assertFalse(t.isAlive(), "the new test directory was never seen");
            assertEquals(new SourceWatcher.Change(false, true), change.get());
        }
    }

    /**
     * A package directory created while the watcher runs, with a file written into it at once, as {@code mkdir -p}
     * followed by an editor's save does: the file is seen, and so is a later edit of it (BUG-20261030-03, reported by
     * the vidocq-tutorial session against {@code vidocq dev}).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void a_package_created_later_and_its_files_are_watched(@TempDir Path tmp) throws Exception {
        Path test = Files.createDirectories(tmp.resolve("src/test/java"));
        Path library = Files.createDirectories(test.resolve("com/example/library"));
        Files.writeString(library.resolve("HelloTest.java"), "class HelloTest {}");

        try (SourceWatcher watcher = SourceWatcher.on(List.of(), List.of(test), Duration.ofMillis(50))) {
            Path book = library.resolve("book");
            Files.createDirectories(book);
            Path bookTest = book.resolve("BookTest.java");
            Files.writeString(bookTest, "class BookTest {}");
            assertEquals(new SourceWatcher.Change(false, true), awaitWithin(watcher, 25),
                    "the new package's first file was missed");

            Thread.sleep(1_100); // a modification time the polling watcher of macOS tells from the first one
            Files.writeString(bookTest, "class BookTest { /* edit */ }");
            assertEquals(new SourceWatcher.Change(false, true), awaitWithin(watcher, 25),
                    "an edit in the new package was missed");
        }
    }

    /** {@link SourceWatcher#awaitChanges} on another thread, or {@code null} after {@code seconds}. */
    private static SourceWatcher.Change awaitWithin(SourceWatcher watcher, int seconds) throws InterruptedException {
        AtomicReference<SourceWatcher.Change> change = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                change.set(watcher.awaitChanges());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        t.start();
        t.join(TimeUnit.SECONDS.toMillis(seconds));
        if (t.isAlive()) {
            t.interrupt();
        }
        return change.get();
    }

    /** The events of a burst, a main and a test edit and a new package, collapse into one change of both kinds. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void a_burst_within_the_debounce_window_is_one_change(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve(BURST_MAIN));
        Path test = Files.createDirectories(tmp.resolve(BURST_TEST));
        Path mainFile = main.resolve("Hello.java");
        Path testFile = test.resolve("HelloTest.java");
        Files.writeString(mainFile, "class Hello { /* before the burst */ }");
        Files.writeString(testFile, "class HelloTest { /* before the burst */ }");

        try (SourceWatcher watcher = SourceWatcher.on(List.of(main), List.of(test), Duration.ofSeconds(4))) {
            // The directories are registered: the events queue up until awaitChanges takes them.
            Files.writeString(mainFile, "class Hello { /* burst */ }");
            Files.createDirectories(main.resolve("pkg"));
            Files.writeString(main.resolve("pkg/World.java"), "package pkg; class World {}");
            Files.writeString(testFile, "class HelloTest { /* burst */ }");

            assertEquals(new SourceWatcher.Change(true, true), watcher.awaitChanges());
        }
    }

    /** Waits until {@code watching} blocks in the watcher, waiting for an event. */
    private static void awaitBlocked(Thread watching) throws InterruptedException {
        assertTrue(Poll.until(Duration.ofSeconds(5), () -> watching.getState() == Thread.State.WAITING
                || watching.getState() == Thread.State.TIMED_WAITING), "the watcher waits for a change");
    }

    @Test
    void noDirectoryAtAllIsNoError() {
        assertDoesNotThrow(() -> {
            try (var _ = SourceWatcher.on(List.of(), List.of(), Duration.ofMillis(50))) {
                // nothing to watch, and no exception
            }
        });
    }
}
