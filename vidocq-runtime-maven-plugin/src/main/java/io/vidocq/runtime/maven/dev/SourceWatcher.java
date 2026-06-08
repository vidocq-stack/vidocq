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
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Recursive {@link WatchService} wrapper used by {@code vidocq:dev} to detect
 * source modifications under {@code src/main/{java,resources}}.
 *
 * <p>Events are filtered on a fixed set of extensions ({@code .java},
 * {@code .properties}, {@code .xml}, {@code .yml}/{@code .yaml}) so that
 * IDE temp files or compile output never trigger a reload.</p>
 *
 * <p>{@link #awaitChange()} blocks until a relevant event arrives, then waits
 * {@code debounce} additional millis draining any follow-up events. That way,
 * saving a file 10 times in 500 ms still triggers a single reload cycle.</p>
 *
 * <p><b>macOS caveat</b> — {@code java.nio.file.WatchService} uses polling on
 * macOS (~10 s latency). This is accepted for the MVP; a future iteration may
 * plug FSEvents natively.</p>
 */
final class SourceWatcher implements AutoCloseable {

    private static final Set<String> WATCHED_EXTENSIONS =
            Set.of(".java", ".properties", ".xml", ".yml", ".yaml");

    private final WatchService service;
    private final long debounceMillis;
    private final Set<Path> registered = new HashSet<>();

    private SourceWatcher(WatchService service, long debounceMillis) {
        this.service = service;
        this.debounceMillis = debounceMillis;
    }

    /**
     * Register the given directories (recursively) and return a ready-to-use
     * watcher. Non-existent directories are silently skipped.
     */
    static SourceWatcher on(List<Path> dirs, Duration debounce) throws IOException {
        WatchService ws = dirs.get(0).getFileSystem().newWatchService();
        SourceWatcher watcher = new SourceWatcher(ws, debounce.toMillis());
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                watcher.registerRecursive(dir);
            }
        }
        return watcher;
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                dir.register(service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                registered.add(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Block until at least one event on a watched file extension arrives,
     * then drain any further event within the debounce window. Returns
     * {@code true} we have a real change, {@code false} if the watcher was closed.
     */
    boolean awaitChange() throws InterruptedException {
        while (true) {
            WatchKey key;
            try {
                key = service.take();
            } catch (ClosedWatchServiceException e) {
                return false;
            }
            boolean relevant = drainKey(key);
            if (!key.reset()) {
                registered.remove((Path) key.watchable());
            }
            if (!relevant) {
                continue;
            }
            // Debounce: keep draining events for `debounceMillis` so a burst
            // of editor saves collapses into one reload cycle.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMillis);
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                WatchKey more;
                try {
                    more = service.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (ClosedWatchServiceException e) {
                    return true;
                }
                if (more == null) {
                    break;
                }
                drainKey(more);
                if (!more.reset()) {
                    registered.remove((Path) more.watchable());
                }
            }
            return true;
        }
    }

    /**
     * Pull all events for {@code key} and return whether any of them concerns
     * a file we actually care about. Also auto-registers any newly created
     * sub-directory so the recursive watch stays in sync.
     */
    private boolean drainKey(WatchKey key) {
        boolean relevant = false;
        Path parent = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                relevant = true;
                continue;
            }
            Object ctx = event.context();
            if (!(ctx instanceof Path child)) {
                continue;
            }
            Path resolved = parent.resolve(child);
            if (Files.isDirectory(resolved)
                    && event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && !registered.contains(resolved)) {
                try {
                    registerRecursive(resolved);
                } catch (IOException ignored) {
                    // a new subdir we cannot register is not fatal
                }
            }
            if (isWatchedExtension(child.toString())) {
                relevant = true;
            }
        }
        return relevant;
    }

    private static boolean isWatchedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return WATCHED_EXTENSIONS.contains(name.substring(dot).toLowerCase());
    }

    @Override
    public void close() throws IOException {
        service.close();
    }
}
