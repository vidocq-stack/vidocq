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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The sources saved while a compile ran, which the compiler plugin would never compile (BUG-20261117-03).
 *
 * <p>The compiler plugin takes a source for compiled when its class is not older. javac reads every source first and
 * writes the classes last, seconds later on a real module. A source saved in between is older than the class javac
 * then writes from what it read before the save: no later build compiles it, and the save reaches the application,
 * or the tests, only when a source is saved again, which makes the plugin compile the module.
 *
 * <p>Nothing says what javac read. So the time of each source is noted when a compile starts, and the time of each
 * class when it ends. When the next compile starts, a source whose time changed since, and whose class is the one
 * the previous compile left, not older than the source, is one the compiler plugin would skip. {@link #compileStarts}
 * deletes that class: the plugin finds a source without a class and compiles the module, as it does for any change.
 *
 * <ul>
 *   <li>A source saved before javac read it is compiled twice. The second compile writes the same classes, and
 *       nothing reloads.</li>
 *   <li>A class written after the compile ended is another build's, an IDE that builds on save for instance: it was
 *       compiled from the save, and it stays.</li>
 * </ul>
 *
 * <p>One compile, one thread: the recompile of the dev loop has its own, and so has each output of the test runs.
 */
final class SavedWhileCompiling {

    private static final String SOURCE = ".java";
    private static final String CLASS = ".class";

    /** How many sources a message names. */
    private static final int MOST_NAMED = 3;

    private final List<Path> sourceRoots;
    private final Path output;
    private final String compile;
    private final Consumer<String> info;
    private final Consumer<String> warn;

    /** The time of each source when the last compile started; {@code null} until a first compile starts. */
    private Map<Path, FileTime> noted;

    /**
     * The time of the class of each source when the last compile ended; {@code null} when its end is unknown, and
     * every class is then in doubt.
     */
    private Map<Path, FileTime> left;

    /** When a compile nobody noted started; {@code null} when there was none, and once a compile started since. */
    private FileTime earlier;

    /** That compile, as the messages name it. */
    private String earlierName;

    /**
     * @param sourceRoots the directories the compiler plugin compiles; one that does not exist holds no source
     * @param output      where it writes their classes
     * @param compile     what compiles them, as the messages name it: {@code recompile}, {@code run}
     * @param info        gets the sources a compile that starts compiles again, by name, three at most
     * @param warn        gets what to do when a time cannot be read or a class cannot be deleted
     */
    SavedWhileCompiling(List<Path> sourceRoots, Path output, String compile, Consumer<String> info,
            Consumer<String> warn) {
        this.sourceRoots = List.copyOf(sourceRoots);
        this.output = output;
        this.compile = compile;
        this.info = info;
        this.warn = warn;
    }

    /**
     * To call before the first {@link #compileStarts()} when a compile ran that nobody noted, the build that precedes
     * a dev session for instance (BUG-20261117-05). Of its sources only the time it started is known: every source
     * saved since, in milliseconds, is taken for saved while it ran, and every class is in doubt. It counts once: the
     * compile that starts notes the times, and the next one compares with them.
     *
     * @param name    that compile, as the messages name it: {@code the build}
     * @param started when it started
     */
    void anEarlierCompileStarted(String name, FileTime started) {
        earlier = started;
        earlierName = name;
    }

    /**
     * To call when a compile of the sources starts, before its Maven does. Deletes the class of each source that was
     * saved since the previous call and that the compiler plugin would skip, says so, and returns those sources, in
     * the order of their paths. The first call has nothing to compare with, unless {@link #anEarlierCompileStarted}
     * told of a compile before it: it notes the times and returns nothing. A failure does not stop the compile: it is
     * a warning, and nothing is returned.
     */
    List<Path> compileStarts() {
        String previous = earlier != null ? earlierName : "the previous " + compile;
        List<Path> skipped;
        try {
            skipped = deleteClassesInDoubt();
        } catch (IOException e) {
            warn.accept("Cannot tell whether a source was saved since " + previous + " started (" + e
                    + "): if a save does not show, save the file again.");
            return List.of();
        }
        if (skipped.size() == 1) {
            info.accept(skipped.getFirst().getFileName() + " was saved since " + previous
                    + " started and its class is not older: compiling it again.");
        } else if (!skipped.isEmpty()) {
            info.accept(skipped.size() + " sources were saved since " + previous
                    + " started and their classes are not older (" + names(skipped) + "): compiling them again.");
        }
        return skipped;
    }

    /**
     * To call when the compile ended, whatever its outcome: notes the classes it leaves. A class that changes after
     * this call is another build's.
     */
    void compileEnds() {
        Map<Path, FileTime> classes = new HashMap<>();
        try {
            eachSource((source, saved, itsClass) -> {
                FileTime written = time(itsClass);
                if (written != null) {
                    classes.put(source, written);
                }
            });
            left = classes;
        } catch (IOException _) {
            left = null;
        }
    }

    private List<Path> deleteClassesInDoubt() throws IOException {
        Map<Path, FileTime> times = new HashMap<>();
        List<Path> skipped = new ArrayList<>();
        eachSource((source, saved, itsClass) -> {
            times.put(source, saved);
            if (savedSinceTheLastStart(source, saved) && inDoubt(source, saved, itsClass)) {
                Files.deleteIfExists(itsClass);
                skipped.add(source);
            }
        });
        noted = times;
        earlier = null;
        left = null;
        return skipped.stream().sorted().toList();
    }

    /**
     * Whether {@code source} was saved since the compile before this one started: its time is not the one noted
     * then, or, for a compile nobody noted, not before that compile started.
     */
    private boolean savedSinceTheLastStart(Path source, FileTime saved) {
        if (noted != null) {
            return !saved.equals(noted.get(source));
        }
        return earlier != null && saved.toMillis() >= earlier.toMillis();
    }

    /**
     * Whether the compiler plugin takes {@code source} for compiled, with a class the previous compile left: the
     * class is there, not older than the source in milliseconds, as the plugin reads the times, and unchanged since
     * that compile ended.
     */
    private boolean inDoubt(Path source, FileTime saved, Path itsClass) throws IOException {
        FileTime written = time(itsClass);
        if (written == null || written.toMillis() < saved.toMillis()) {
            return false;
        }
        return left == null || written.equals(left.get(source));
    }

    /** The time of {@code file}, or {@code null} when there is no such file. */
    private static FileTime time(Path file) throws IOException {
        try {
            return Files.getLastModifiedTime(file);
        } catch (NoSuchFileException _) {
            return null;
        }
    }

    /** The names of the first files, three at most, and a mark when there are more. */
    static String names(List<Path> sources) {
        String first = sources.stream().limit(MOST_NAMED).map(source -> source.getFileName().toString())
                .collect(Collectors.joining(", "));
        return sources.size() > MOST_NAMED ? first + ", …" : first;
    }

    /** Gives {@code each} every Java source of the roots, with its time and the class the plugin compares it with. */
    private void eachSource(EachSource each) throws IOException {
        for (Path root : sourceRoots) {
            if (Files.isDirectory(root)) {
                Files.walkFileTree(root, new Sources(root, each));
            }
        }
    }

    @FunctionalInterface
    private interface EachSource {
        void accept(Path source, FileTime saved, Path itsClass) throws IOException;
    }

    /** The Java sources of one root. */
    private final class Sources extends SimpleFileVisitor<Path> {

        private final Path root;
        private final EachSource each;

        Sources(Path root, EachSource each) {
            this.root = root;
            this.each = each;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            String name = file.getFileName().toString();
            if (name.endsWith(SOURCE)) {
                Path itsClass = output.resolve(root.relativize(file))
                        .resolveSibling(name.substring(0, name.length() - SOURCE.length()) + CLASS);
                each.accept(file, attributes.lastModifiedTime(), itsClass);
            }
            return FileVisitResult.CONTINUE;
        }

        /** A file deleted while its directory is read is one file less; any other failure is the caller's. */
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            if (failure instanceof NoSuchFileException) {
                return FileVisitResult.CONTINUE;
            }
            throw failure;
        }
    }
}
