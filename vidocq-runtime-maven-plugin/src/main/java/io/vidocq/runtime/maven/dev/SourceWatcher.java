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
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Recursive {@link WatchService} wrapper used by {@code vidocq:dev} and {@code vidocq:test} to detect
 * source modifications under the main directories and, for continuous testing, the test directories;
 * {@link #awaitChanges} says which of the two a burst touched.
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

    /** A burst of events: whether it touched the main directories, the test directories, or both. */
    record Change(boolean main, boolean test) {}

    private final List<Path> testRoots;
    /** The watched roots that did not exist yet: looked for every {@value #MISSING_ROOT_POLL_MILLIS} ms (#138). */
    private final List<Path> missing = new ArrayList<>();

    /** How often a watched root that does not exist yet is looked for. */
    static final long MISSING_ROOT_POLL_MILLIS = 1_000;

    private SourceWatcher(WatchService service, long debounceMillis, List<Path> testRoots) {
        this.service = service;
        this.debounceMillis = debounceMillis;
        this.testRoots = testRoots;
    }

    /** A watcher of main directories only. */
    static SourceWatcher on(List<Path> dirs, Duration debounce) throws IOException {
        return on(dirs, List.of(), debounce);
    }

    /**
     * Registers the main and test directories (recursively). A directory that does not exist is skipped.
     */
    static SourceWatcher on(List<Path> mainDirs, List<Path> testDirs, Duration debounce) throws IOException {
        List<Path> all = new ArrayList<>(mainDirs);
        all.addAll(testDirs);
        WatchService ws = (all.isEmpty() ? FileSystems.getDefault() : all.get(0).getFileSystem()).newWatchService();
        SourceWatcher watcher = new SourceWatcher(ws, debounce.toMillis(),
                testDirs.stream().map(dir -> dir.toAbsolutePath().normalize()).toList());
        for (Path dir : all) {
            if (Files.isDirectory(dir)) {
                watcher.registerRecursive(dir);
            } else {
                watcher.missing.add(dir.toAbsolutePath().normalize());
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

    /** {@link #awaitChanges}, as a yes/no: {@code false} once the watcher is closed. */
    boolean awaitChange() throws InterruptedException {
        return awaitChanges() != null;
    }

    /**
     * Blocks until at least one event on a watched file extension arrives, then drains any further event within
     * the debounce window. Returns what the burst touched, or {@code null} once the watcher is closed.
     */
    Change awaitChanges() throws InterruptedException {
        while (true) {
            WatchKey key;
            try {
                key = missing.isEmpty() ? service.take()
                        : service.poll(MISSING_ROOT_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (ClosedWatchServiceException e) {
                return null;
            }
            Touched touched = new Touched();
            if (key == null) {
                registerAppearedRoots(touched);
                if (touched.any()) {
                    return touched.change();
                }
                continue;
            }
            drainKey(key, touched);
            if (!key.reset()) {
                registered.remove((Path) key.watchable());
            }
            if (!touched.any()) {
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
                    return touched.change();
                }
                if (more == null) {
                    break;
                }
                drainKey(more, touched);
                if (!more.reset()) {
                    registered.remove((Path) more.watchable());
                }
            }
            return touched.change();
        }
    }

    /** What the events drained so far touched. */
    private static final class Touched {
        boolean main;
        boolean test;

        boolean any() {
            return main || test;
        }

        Change change() {
            return new Change(main, test);
        }
    }

    /**
     * Pull all events for {@code key} and note whether they concern a watched file of a main or a test directory;
     * an overflow counts as both. Also auto-registers any newly created sub-directory so the recursive watch stays
     * in sync.
     */
    private void drainKey(WatchKey key, Touched touched) {
        Path parent = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                touched.main = true;
                touched.test = !testRoots.isEmpty();
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
                if (under(resolved, testRoots)) {
                    touched.test = true;
                } else {
                    touched.main = true;
                }
            }
        }
    }

    /**
     * Registers the watched roots that appeared since the last look. One that holds a watched file is a change of its
     * kind: a first {@code src/test/java} created with a test in it runs the tests.
     */
    private void registerAppearedRoots(Touched touched) {
        for (var it = missing.iterator(); it.hasNext(); ) {
            Path root = it.next();
            if (!Files.isDirectory(root)) {
                continue;
            }
            it.remove();
            try {
                registerRecursive(root);
                boolean sources;
                try (Stream<Path> files = Files.walk(root)) {
                    sources = files.anyMatch(file -> isWatchedExtension(file.getFileName().toString()));
                }
                if (sources) {
                    if (under(root, testRoots)) {
                        touched.test = true;
                    } else {
                        touched.main = true;
                    }
                }
            } catch (IOException | UncheckedIOException ignored) {
                // a root we cannot read yet is not fatal: its later events still count once registered
            }
        }
    }

    /** Whether {@code file} lies under one of {@code roots} (absolute, normalized). */
    static boolean under(Path file, List<Path> roots) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
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
