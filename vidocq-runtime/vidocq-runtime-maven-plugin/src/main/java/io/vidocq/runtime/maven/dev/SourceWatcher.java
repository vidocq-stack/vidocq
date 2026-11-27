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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
 * <p><b>macOS</b> — the JDK's {@code WatchService} of macOS ({@code sun.nio.fs.PollingWatchService}) looks at the
 * directories every 2 s on JDK 25, so a save waits up to 2 s before it is seen (Vidocq/vidocq#651). Where the
 * JDK's watcher polls, {@link #on(List, List, Duration, Duration)} reads the modification times and sizes of the
 * watched files itself at a shorter interval ({@link FileTimes}), and reports the changes as the JDK's watcher would,
 * with the same debounce. Linux's watcher is told of each change by the kernel and stays.</p>
 *
 * <p><b>Watched files</b> — besides the directories, single files can be watched, alone of their directory: the
 * {@code pom.xml} of {@code vidocq:dev} (Vidocq/vidocq#799). A save of one is a main change; nothing else of its
 * directory is watched, and a directory created there, such as {@code target/}, is not registered.</p>
 */
final class SourceWatcher implements AutoCloseable {

    private static final Set<String> WATCHED_EXTENSIONS =
            Set.of(".java", ".properties", ".xml", ".yml", ".yaml");

    /** The class of the JDK's watcher that polls, as on macOS. */
    private static final String POLLING_WATCH_SERVICE = "sun.nio.fs.PollingWatchService";

    /** The JDK's watcher; {@code null} when this watcher reads the file times itself. */
    private final WatchService service;
    /** The file times read pass after pass; {@code null} with the JDK's watcher. */
    private final FileTimes times;
    private final long debounceMillis;
    private final long pollMillis;
    /** Counted down by {@link #close()}: ends the wait between two passes over the file times. */
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Set<Path> registered = new HashSet<>();

    /** A burst of events: whether it touched the main directories, the test directories, or both. */
    record Change(boolean main, boolean test) {}

    private final List<Path> testRoots;
    /** The single files watched, absolute and normalized: a save of one is a main change. */
    private final Set<Path> files;
    /** The watched roots that did not exist yet: looked for every {@value #MISSING_ROOT_POLL_MILLIS} ms (#138). */
    private final List<Path> missing = new ArrayList<>();

    /** How often a watched root that does not exist yet is looked for. */
    static final long MISSING_ROOT_POLL_MILLIS = 1_000;

    private SourceWatcher(WatchService service, FileTimes times, long debounceMillis, long pollMillis,
            List<Path> testRoots, List<Path> files) {
        this.service = service;
        this.times = times;
        this.debounceMillis = debounceMillis;
        this.pollMillis = pollMillis;
        this.testRoots = testRoots;
        this.files = Set.copyOf(normalized(files));
    }

    private static List<Path> normalized(List<Path> paths) {
        return paths.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    /** A watcher of main directories only, with the JDK's watcher. */
    static SourceWatcher on(List<Path> dirs, Duration debounce) throws IOException {
        return on(dirs, List.of(), debounce);
    }

    /**
     * Registers the main and test directories (recursively) with the JDK's watcher, whichever it is. A directory that
     * does not exist is skipped.
     */
    static SourceWatcher on(List<Path> mainDirs, List<Path> testDirs, Duration debounce) throws IOException {
        return on(mainDirs, testDirs, List.of(), debounce);
    }

    /** {@link #on(List, List, Duration)}, and {@code files}, each alone of its directory. */
    private static SourceWatcher on(List<Path> mainDirs, List<Path> testDirs, List<Path> files, Duration debounce)
            throws IOException {
        List<Path> all = new ArrayList<>(mainDirs);
        all.addAll(testDirs);
        WatchService ws = (all.isEmpty() ? FileSystems.getDefault() : all.get(0).getFileSystem()).newWatchService();
        SourceWatcher watcher = new SourceWatcher(ws, null, debounce.toMillis(), 0, normalized(testDirs), files);
        try {
            watcher.registerAll(all);
            watcher.registerFiles();
        } catch (IOException | RuntimeException e) {
            watcher.close(); // a half-registered watcher would leak its WatchService
            throw e;
        }
        return watcher;
    }

    /**
     * Watches the main and test directories with the JDK's watcher, unless it polls, as on macOS: then the watcher
     * reads the file times itself every {@code poll} (Vidocq/vidocq#651). A {@code poll} of zero keeps the JDK's
     * watcher everywhere.
     */
    static SourceWatcher on(List<Path> mainDirs, List<Path> testDirs, Duration debounce, Duration poll)
            throws IOException {
        return on(mainDirs, testDirs, List.of(), debounce, poll);
    }

    /** {@link #on(List, List, Duration, Duration)}, and {@code files}, each alone of its directory. */
    static SourceWatcher on(List<Path> mainDirs, List<Path> testDirs, List<Path> files, Duration debounce,
            Duration poll) throws IOException {
        if (poll.isPositive()) {
            List<Path> all = new ArrayList<>(mainDirs);
            all.addAll(testDirs);
            boolean polls;
            try (WatchService jdk = (all.isEmpty() ? FileSystems.getDefault() : all.get(0).getFileSystem())
                    .newWatchService()) {
                polls = pollsItself(jdk);
            }
            if (polls) {
                return readingFileTimes(mainDirs, testDirs, files, debounce, poll);
            }
        }
        return on(mainDirs, testDirs, files, debounce);
    }

    /** A watcher that reads the file times of the directories every {@code poll}, whatever the JDK's watcher. */
    static SourceWatcher readingFileTimes(List<Path> mainDirs, List<Path> testDirs, Duration debounce, Duration poll) {
        return readingFileTimes(mainDirs, testDirs, List.of(), debounce, poll);
    }

    /** {@link #readingFileTimes(List, List, Duration, Duration)}, and {@code files}. */
    static SourceWatcher readingFileTimes(List<Path> mainDirs, List<Path> testDirs, List<Path> files,
            Duration debounce, Duration poll) {
        return new SourceWatcher(null, new FileTimes(mainDirs, testDirs, files), debounce.toMillis(),
                poll.toMillis(), normalized(testDirs), files);
    }

    /**
     * Whether the JDK's watcher looks at the directories at an interval instead of being told of each change: the
     * {@code sun.nio.fs.PollingWatchService} of macOS, which looks every 2 s. Its class name is the only sign of it.
     */
    static boolean pollsItself(WatchService jdk) {
        return POLLING_WATCH_SERVICE.equals(jdk.getClass().getName());
    }

    /** Whether this watcher reads the file times itself instead of waiting for the JDK's watcher. */
    boolean readsFileTimes() {
        return times != null;
    }

    private void registerAll(List<Path> dirs) throws IOException {
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                registerRecursive(dir);
            } else {
                missing.add(dir.toAbsolutePath().normalize());
            }
        }
    }

    /**
     * Registers the directory of each watched file, alone: {@link #drainKey} reads nothing else of it, unless it is a
     * watched directory too. A file whose directory does not exist is not watched.
     */
    private void registerFiles() throws IOException {
        for (Path parent : files.stream().map(Path::getParent).distinct().toList()) {
            if (Files.isDirectory(parent)) {
                parent.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
            }
        }
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
        return times == null ? awaitEvents() : awaitFileTimes();
    }

    /**
     * {@link #awaitChanges} by reading the file times every {@code pollMillis} on the calling thread: no thread of
     * its own. The debounce window then collects the changes of the passes it spans.
     */
    private Change awaitFileTimes() throws InterruptedException {
        while (!closed.await(pollMillis, TimeUnit.MILLISECONDS)) {
            Change first = times.pass();
            if (first.main() || first.test()) {
                return debounceFileTimes(first);
            }
        }
        return null;
    }

    private Change debounceFileTimes(Change first) throws InterruptedException {
        boolean main = first.main();
        boolean test = first.test();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMillis);
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0
                && !closed.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(pollMillis)), TimeUnit.NANOSECONDS)) {
            Change more = times.pass();
            main |= more.main();
            test |= more.test();
        }
        return new Change(main, test);
    }

    /** {@link #awaitChanges} with the JDK's watcher. */
    private Change awaitEvents() throws InterruptedException {
        while (true) {
            WatchKey key;
            try {
                key = missing.isEmpty() ? service.take()
                        : service.poll(MISSING_ROOT_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (ClosedWatchServiceException _) {
                return null;
            }
            Touched touched = new Touched();
            if (key == null) {
                registerAppearedRoots(touched);
            } else {
                drainAndReset(key, touched);
            }
            if (touched.any()) {
                return key == null ? touched.change() : debounce(touched);
            }
        }
    }

    /**
     * Keeps draining events for {@code debounceMillis} so a burst of editor saves collapses into one reload cycle.
     */
    private Change debounce(Touched touched) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMillis);
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            WatchKey more;
            try {
                more = service.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (ClosedWatchServiceException _) {
                return touched.change();
            }
            if (more == null) {
                return touched.change();
            }
            drainAndReset(more, touched);
        }
        return touched.change();
    }

    /** {@link #drainKey}, then resets the key; one that is no longer valid forgets its directory. */
    private void drainAndReset(WatchKey key, Touched touched) {
        drainKey(key, touched);
        if (!key.reset()) {
            registered.remove(key.watchable());
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
            } else if (event.context() instanceof Path child) {
                if (files.contains(parent.resolve(child).toAbsolutePath().normalize())) {
                    touched.main = true;
                } else if (registered.contains(parent)) {
                    noteEvent(parent, child, event.kind(), touched);
                }
            }
        }
    }

    /** Notes one event on {@code child} of {@code parent}, registering a newly created sub-directory. */
    private void noteEvent(Path parent, Path child, WatchEvent.Kind<?> kind, Touched touched) {
        Path resolved = parent.resolve(child);
        if (Files.isDirectory(resolved)
                && kind == StandardWatchEventKinds.ENTRY_CREATE
                && !registered.contains(resolved)) {
            try {
                registerRecursive(resolved);
                // Files written into it before it was registered raised no event of their own: a `mkdir -p` and a
                // save land well within the polling interval of macOS (BUG-20261030-03).
                noteSourcesIn(resolved, touched);
            } catch (IOException _) {
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

    /** Notes the watched files already under {@code dir}, of a main or a test directory. */
    private void noteSourcesIn(Path dir, Touched touched) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            if (files.anyMatch(file -> isWatchedExtension(file.getFileName().toString()))) {
                if (under(dir, testRoots)) {
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
                noteSourcesIn(root, touched);
            } catch (IOException | UncheckedIOException _) {
                // a root we cannot read yet is not fatal: its later events still count once registered
            }
        }
    }

    /**
     * The watched files of {@code dirs} saved since {@code since}, in milliseconds, in the order of their paths: what
     * a watcher would have reported, had it been there from that time (BUG-20261117-05). A directory that does not
     * exist holds no file.
     */
    static List<Path> savedSince(List<Path> dirs, FileTime since) throws IOException {
        return savedBetween(dirs, since, null);
    }

    /**
     * The watched files of {@code dirs} saved from {@code since} to {@code before}, the latter left out, in
     * milliseconds, in the order of their paths: what changed from one time to another (BUG-20261117-07). Without a
     * {@code before}, every file saved since {@code since}. A directory that does not exist holds no file.
     */
    static List<Path> savedBetween(List<Path> dirs, FileTime since, FileTime before) throws IOException {
        long from = since.toMillis();
        long to = before == null ? Long.MAX_VALUE : before.toMillis();
        List<Path> saved = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.find(dir, Integer.MAX_VALUE, (file, attributes) ->
                    attributes.isRegularFile() && isWatchedExtension(file.getFileName().toString())
                            && between(attributes.lastModifiedTime().toMillis(), from, to))) {
                files.forEach(saved::add);
            } catch (UncheckedIOException e) {   // Files.find reports an unreadable directory unchecked
                throw e.getCause();
            }
        }
        return saved.stream().sorted().toList();
    }

    private static boolean between(long time, long from, long to) {
        return time >= from && time < to;
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

    static boolean isWatchedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return WATCHED_EXTENSIONS.contains(name.substring(dot).toLowerCase());
    }

    @Override
    public void close() throws IOException {
        closed.countDown();
        if (service != null) {
            service.close();
        }
    }
}
