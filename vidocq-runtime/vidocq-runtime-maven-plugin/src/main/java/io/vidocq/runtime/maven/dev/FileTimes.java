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
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The modification time and size of every watched file under some roots, read pass after pass: what
 * {@link SourceWatcher} looks at where the JDK's watcher polls, as on macOS, where it looks only every 2 s
 * (Vidocq/vidocq#651). A pass reports a file that is new, gone, or whose time or size changed since the previous
 * pass. A root that does not exist holds no file, and is read once it appears.
 */
final class FileTimes {

    /** What a pass notes of a file; the size tells two saves within the resolution of the file times apart. */
    private record Stamp(FileTime time, long size) {}

    private final List<Path> roots;
    private final List<Path> testRoots;
    private Map<Path, Stamp> last;

    /** Reads the files of the main and test roots: the first pass compares with this one. */
    FileTimes(List<Path> mainRoots, List<Path> testRoots) {
        List<Path> all = new ArrayList<>(mainRoots);
        all.addAll(testRoots);
        this.roots = all.stream().map(root -> root.toAbsolutePath().normalize()).toList();
        this.testRoots = testRoots.stream().map(root -> root.toAbsolutePath().normalize()).toList();
        this.last = read();
    }

    /** Reads the files again, and says whether a file of a main or a test root changed since the previous pass. */
    SourceWatcher.Change pass() {
        Map<Path, Stamp> now = read();
        boolean main = false;
        boolean test = false;
        List<Path> changed = new ArrayList<>();
        now.forEach((file, stamp) -> {
            if (!stamp.equals(last.get(file))) {
                changed.add(file);
            }
        });
        last.keySet().stream().filter(file -> !now.containsKey(file)).forEach(changed::add);
        for (Path file : changed) {
            if (SourceWatcher.under(file, testRoots)) {
                test = true;
            } else {
                main = true;
            }
        }
        last = now;
        return new SourceWatcher.Change(main, test);
    }

    private Map<Path, Stamp> read() {
        Map<Path, Stamp> files = new HashMap<>();
        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && SourceWatcher.isWatchedExtension(file.getFileName().toString())) {
                    files.put(file, new Stamp(attributes.lastModifiedTime(), attributes.size()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // gone while the pass ran, or unreadable: the next pass reads it
            }
        };
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try {
                    Files.walkFileTree(root, visitor);
                } catch (IOException _) {
                    // the visitor goes on past every failure: nothing to report
                }
            }
        }
        return files;
    }
}
