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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A project of the hot swap tests: its sources under {@code src}, compiled by javac into {@code classes}, as a
 * recompile of {@code vidocq:dev} leaves them. Generated sources go under {@code gen}, outside the source roots the
 * classifier is given; raw class files and resources are written as they are.
 */
final class HotSwapProject {

    final Path src;
    final Path gen;
    final Path classes;
    private final Path dir;
    private final List<Path> classpath;
    private int builds;

    HotSwapProject(Path dir, List<Path> classpath) {
        this.dir = dir;
        this.src = dir.resolve("src");
        this.gen = dir.resolve("gen");
        this.classes = dir.resolve("classes");
        this.classpath = classpath;
    }

    /** One build of the project. */
    static Build build() {
        return new Build();
    }

    /** What one build compiles and writes. */
    static final class Build {
        final Map<String, String> sources = new LinkedHashMap<>();
        final Map<String, String> generated = new LinkedHashMap<>();
        final Map<String, byte[]> classFiles = new LinkedHashMap<>();
        final Map<String, String> resources = new LinkedHashMap<>();

        Build source(String path, String content) {
            sources.put(path, content);
            return this;
        }

        Build generated(String path, String content) {
            generated.put(path, content);
            return this;
        }

        Build classFile(String binaryName, byte[] bytes) {
            classFiles.put(binaryName, bytes);
            return this;
        }

        Build resource(String path, String content) {
            resources.put(path, content);
            return this;
        }
    }

    /** Builds into {@code classes}, replacing what the previous build left, and snapshots it. */
    ClassesSnapshot apply(Build build) {
        try {
            clean(src);
            clean(gen);
            clean(classes);
            write(src, build.sources);
            write(gen, build.generated);
            var all = new HashMap<String, String>(build.sources);
            all.putAll(build.generated);
            if (!all.isEmpty()) {
                Path out = HotSwapFixtures.compile(dir, "build" + builds++, all, classpath);
                try (Stream<Path> walk = Files.walk(out)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        Path target = classes.resolve(out.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
                }
            }
            for (var e : build.classFiles.entrySet()) {
                Path target = classes.resolve(e.getKey().replace('.', '/') + ".class");
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue());
            }
            write(classes, build.resources);
            return ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path root, Map<String, String> files) throws IOException {
        for (var e : files.entrySet()) {
            Path file = root.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
    }

    private static void clean(Path root) throws IOException {
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(root);
    }
}
