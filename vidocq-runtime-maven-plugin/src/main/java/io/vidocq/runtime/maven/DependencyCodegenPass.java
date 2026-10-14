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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.maven.depgen.Brick;
import io.vidocq.runtime.maven.depgen.DependencyCodegen;
import io.vidocq.runtime.maven.depgen.DependencyCodegenCache;
import io.vidocq.runtime.maven.depgen.DependencyCodegenHints;
import io.vidocq.runtime.maven.depgen.DependencyCodegenSelection;
import org.apache.maven.plugin.MojoFailureException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 * The dependency codegen pass of {@code vidocq:generate}: runs the processors of the bricks found on the compiler's
 * {@code annotationProcessorPaths} over the dependency jars their hints select, once per jar version thanks to the
 * cache, and writes what they generate into the jar's patch directory, next to what Vauban parked there. The
 * enriched copy then carries it.
 */
final class DependencyCodegenPass {

    /** Runs the bricks over one jar; {@link DependencyCodegen#run} outside tests. */
    @FunctionalInterface
    interface Generator {
        DependencyCodegen.Output generate(Path jar, List<Brick> bricks, List<Path> classpath, int release,
                                          String coordinates, Path log, String logDisplay)
                throws DependencyCodegen.Failure, IOException;
    }

    static final Generator JAVAC = DependencyCodegen::run;

    /** Where the javac output of each jar goes, under {@code target/}. */
    static final String LOG_DIR = "vidocq-depgen";

    /**
     * What the pass did.
     *
     * @param processed       one entry per jar that got generated files, with its bricks and their input lines
     * @param mergedResources the union of the bricks' {@code merge} lists
     */
    record Result(List<EnrichedJars.Scanned> processed, Set<String> mergedResources) {
    }

    private final List<Brick> bricks;
    private final DependencyCodegenCache cache;
    private final Generator generator;
    private final Path buildDir;
    private final Path baseDir;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final Consumer<String> error;

    /**
     * @param baseDir the project's base directory, against which failure lines print the log paths
     */
    DependencyCodegenPass(List<Brick> bricks, DependencyCodegenCache cache, Generator generator, Path buildDir,
                          Path baseDir, Consumer<String> info, Consumer<String> warn, Consumer<String> error) {
        this.bricks = List.copyOf(bricks);
        this.cache = cache;
        this.generator = generator;
        this.buildDir = buildDir;
        this.baseDir = baseDir;
        this.info = info;
        this.warn = warn;
        this.error = error;
    }

    /**
     * Runs the pass over {@code dependencies}. The problems of every jar are collected first, then fail the build
     * together, one line each.
     *
     * @param classpath        the jars' compile classpath: every dependency jar or directory of the project
     * @param scanDependencies patterns naming the signed jars to process anyway
     * @param scanExcludes     patterns of jars never processed
     */
    Result run(List<ScanSelection.Dependency> dependencies, List<Path> classpath, int release,
               List<String> scanDependencies, List<String> scanExcludes) throws MojoFailureException, IOException {
        if (bricks.isEmpty()) {
            return new Result(List.of(), Set.of());
        }
        Set<String> mergedResources = DependencyBricks.mergedResources(bricks);
        List<DependencyCodegenHints> hints = bricks.stream().map(Brick::hints).toList();
        List<EnrichedJars.Scanned> processed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (ScanSelection.Dependency dep : dependencies) {
            Path jar = dep.jar();
            if (!Files.isRegularFile(jar) || matchesAny(scanExcludes, dep)) {
                continue;
            }
            var decision = DependencyCodegenSelection.decide(jar, hints);
            if (!decision.selected()) {
                continue;
            }
            List<Brick> selected = bricks.stream()
                    .filter(b -> decision.matches().stream().anyMatch(m -> m.brick() == b.hints())).toList();
            String names = selected.stream().map(b -> b.hints().brick()).collect(Collectors.joining(", "));
            String file = jar.getFileName().toString();
            if (decision.signed() && !matchesAny(scanDependencies, dep)) {
                info.accept("vidocq:generate: " + file + " is signed; not processed for " + names
                        + " (name it in scanDependencies to process it)");
                continue;
            }
            String key = DependencyCodegenCache.key(jar, selected, release);
            Optional<DependencyCodegen.Output> cached = cache.get(key);
            DependencyCodegen.Output output;
            if (cached.isPresent()) {
                output = cached.get();
            } else {
                Path log = buildDir.resolve(LOG_DIR).resolve(dep.artifactId() + ".log");
                try {
                    output = generator.generate(jar, selected, classpath, release,
                            dep.groupId() + ":" + dep.artifactId(), log, baseDir.relativize(log).toString());
                } catch (DependencyCodegen.Failure f) {
                    failures.addAll(f.lines());
                    continue;
                }
                cache.put(key, output);
            }
            output.warnings().forEach(warn);
            List<String> clashes = clashes(jar, output, mergedResources, names, dep);
            if (!clashes.isEmpty()) {
                failures.addAll(clashes);
                continue;
            }
            long classes = output.files().keySet().stream().filter(n -> n.endsWith(".class")).count();
            info.accept("vidocq:generate: " + file + " — " + names + " (" + classes + " classes, "
                    + (cached.isPresent() ? "cached" : "generated") + ")");
            if (output.files().isEmpty()) {
                continue;
            }
            Path patchDir = JpmsPatches.patchDirFor(buildDir, dep.artifactId());
            for (var e : output.files().entrySet()) {
                Path target = patchDir.resolve(e.getKey()).normalize();
                if (!target.startsWith(patchDir)) {
                    throw new IOException(file + ": generated entry " + e.getKey() + " escapes " + patchDir);
                }
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue());
            }
            processed.add(new EnrichedJars.Scanned(jar, dep.artifactId(), dep.coordinates(),
                    selected.stream().map(b -> b.hints().brick()).toList(), DependencyBricks.inputLines(selected)));
        }
        if (!failures.isEmpty()) {
            failures.forEach(error);
            throw new MojoFailureException(String.join("\n", failures));
        }
        return new Result(processed, mergedResources);
    }

    /**
     * Spec §4.3: a generated file the jar already has must be one a brick merges; the jar's own copy would otherwise
     * win silently, and the generated one be lost.
     */
    private static List<String> clashes(Path jar, DependencyCodegen.Output output, Set<String> mergedResources,
                                        String bricks, ScanSelection.Dependency dep) throws IOException {
        List<String> lines = new ArrayList<>();
        try (JarFile in = new JarFile(jar.toFile(), false)) {
            Set<String> entries = Collections.list(in.entries()).stream().map(ZipEntry::getName)
                    .collect(Collectors.toSet());
            for (String name : output.files().keySet()) {
                if (entries.contains(name) && !mergedResources.contains(name)) {
                    lines.add("vidocq:generate: " + jar.getFileName() + " — " + bricks + " generated " + name
                            + ", which the jar already has; it is not a mergeable resource of that brick. Leave the"
                            + " jar out with <scanExcludes>" + dep.groupId() + ":" + dep.artifactId()
                            + "</scanExcludes>.");
                }
            }
        }
        return lines;
    }

    private static boolean matchesAny(List<String> patterns, ScanSelection.Dependency dep) {
        return patterns.stream().anyMatch(p -> ScanSelection.matches(p, dep.groupId(), dep.artifactId()));
    }

    /**
     * One entry per jar, Vauban's first, in their order: a jar both Vauban and a brick processed takes the brick's
     * entry, which carries the bricks. Two entries for one jar would write its copy twice.
     */
    static List<EnrichedJars.Scanned> onePerJar(List<EnrichedJars.Scanned> vauban,
                                                List<EnrichedJars.Scanned> bricks) {
        Map<Path, EnrichedJars.Scanned> byJar = new LinkedHashMap<>();
        vauban.forEach(s -> byJar.put(s.originalJar(), s));
        bricks.forEach(s -> byJar.put(s.originalJar(), s));
        return List.copyOf(byJar.values());
    }

    /** The {@code --release} of the pass: the project's {@code maven.compiler.release}, else the running JDK's. */
    static int release(String property) {
        return property == null || property.isBlank() ? Runtime.version().feature()
                : Integer.parseInt(property.strip());
    }
}
