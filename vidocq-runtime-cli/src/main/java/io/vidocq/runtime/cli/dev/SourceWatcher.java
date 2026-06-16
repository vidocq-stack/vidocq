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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Recursively watches one or more directory trees and fires a callback when a
 * reload-worthy file changes.
 *
 * <p>Each root tree gets its own {@link WatchService} drained by a dedicated
 * <b>virtual thread</b>. Events are filtered through {@link Reloads} and collapsed by a
 * shared {@link Debouncer}, so a noisy save produces a single reload.
 *
 * <p>The decision pipeline is exposed as {@link #onEvent(Path)} so it can be unit-tested
 * deterministically without relying on OS watch-event timing.
 */
public final class SourceWatcher implements AutoCloseable {

    private final List<Path> roots;
    private final Runnable onChange;
    private final Debouncer debouncer;
    private final List<WatchService> services = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public SourceWatcher(List<Path> roots, Runnable onChange) {
        this(roots, onChange, new Debouncer(Duration.ofMillis(300)));
    }

    public SourceWatcher(List<Path> roots, Runnable onChange, Debouncer debouncer) {
        this.roots = List.copyOf(roots);
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        this.debouncer = Objects.requireNonNull(debouncer, "debouncer");
    }

    /** Directory roots that actually exist and will be watched. */
    public List<Path> watchableRoots() {
        return roots.stream().filter(Files::isDirectory).toList();
    }

    /** Registers every tree and starts one virtual thread per root. Idempotent-safe to call once. */
    public void start() {
        running = true;
        for (Path root : watchableRoots()) {
            startRoot(root);
        }
    }

    private void startRoot(Path root) {
        try {
            WatchService service = root.getFileSystem().newWatchService();
            services.add(service);
            registerTree(root, service);
            Thread thread = Thread.ofVirtual()
                    .name("vidocq-dev-watch-" + root.getFileName())
                    .start(() -> drain(service));
            threads.add(thread);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot watch " + root, e);
        }
    }

    private static void registerTree(Path root, WatchService service) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void drain(WatchService service) {
        while (running) {
            WatchKey key;
            try {
                key = service.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return;
            }
            Path dir = (Path) key.watchable();
            for (var event : key.pollEvents()) {
                Object ctx = event.context();
                if (!(ctx instanceof Path relative)) {
                    continue;
                }
                Path changed = dir.resolve(relative);
                // Newly created directories must be watched too.
                if (event.kind() == ENTRY_CREATE && Files.isDirectory(changed)) {
                    try {
                        registerTree(changed, service);
                    } catch (IOException ignored) {
                        // best effort: a sub-tree we could not register simply won't reload
                    }
                }
                onEvent(changed);
            }
            if (!key.reset()) {
                // the watched directory is gone; nothing more to drain from this key
            }
        }
    }

    /**
     * Decision pipeline for a single changed path: reload-worthy + not debounced ⇒ callback.
     * Package-private for deterministic testing.
     */
    void onEvent(Path changed) {
        if (running && Reloads.isReloadable(changed) && debouncer.accept()) {
            onChange.run();
        }
    }

    @Override
    public void close() {
        running = false;
        for (WatchService service : services) {
            try {
                service.close();
            } catch (IOException ignored) {
                // closing on shutdown; nothing to recover
            }
        }
        threads.forEach(Thread::interrupt);
    }
}
