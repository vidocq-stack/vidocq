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
}
