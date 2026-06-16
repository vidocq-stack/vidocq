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
package io.vidocq.runtime.cli.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceWatcherTest {

    @Test
    void dispatchFiltersAndDebounces() {
        AtomicLong clock = new AtomicLong(0);
        AtomicInteger fired = new AtomicInteger();
        var watcher = new SourceWatcher(
                List.of(), fired::incrementAndGet,
                new Debouncer(Duration.ofMillis(300), clock::get));
        watcher.start(); // no roots -> no threads, just flips the running flag

        watcher.onEvent(Path.of("App.java"));     // accepted
        watcher.onEvent(Path.of("Other.java"));   // within window -> collapsed
        watcher.onEvent(Path.of("notes.txt"));    // not reload-worthy -> ignored
        assertEquals(1, fired.get());

        clock.set(Duration.ofMillis(400).toNanos());
        watcher.onEvent(Path.of("App.class"));    // past window -> accepted
        assertEquals(2, fired.get());

        watcher.close();
        watcher.onEvent(Path.of("App.java"));     // stopped -> ignored
        assertEquals(2, fired.get());
    }

    @Test
    void watchableRootsKeepsOnlyExistingDirectories(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist");
        var watcher = new SourceWatcher(List.of(dir, missing), () -> {});

        assertEquals(List.of(dir), watcher.watchableRoots());
    }

    @Test
    void detectsRealFilesystemChange(@TempDir Path dir) throws IOException, InterruptedException {
        Path src = Files.createDirectories(dir.resolve("src"));
        CountDownLatch hit = new CountDownLatch(1);
        try (var watcher = new SourceWatcher(List.of(src), hit::countDown)) {
            watcher.start();
            // Give the watch service a moment to register before mutating.
            Thread.sleep(200);
            Files.writeString(src.resolve("App.java"), "class App {}");

            assertTrue(hit.await(20, TimeUnit.SECONDS),
                    "watcher should observe the new source file");
        }
    }
}
