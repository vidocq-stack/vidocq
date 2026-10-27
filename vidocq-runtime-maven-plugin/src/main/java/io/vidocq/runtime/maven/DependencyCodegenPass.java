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
        DependencyCodegen.Output generate(Path jar, List<Brick> bricks, List<String> classes, List<Path> classpath,
                                          int release, String coordinates, Path log, String logDisplay)
                throws DependencyCodegen.Failure, IOException;
    }

    static final Generator JAVAC = DependencyCodegen::run;

    /** Where the javac output of each jar goes, under {@code target/}. */
    static final String LOG_DIR = "vidocq-depgen";

    /**
     * Under {@link #LOG_DIR}: one {@code <artifactId>.list} per jar, the files the pass wrote into its patch
     * directory, kept outside it so that the enriched copy never carries it.
     */
    static final String WRITTEN_DIR = "written";
    private static final String WRITTEN_SUFFIX = ".list";
    private static final String LOG_PREFIX = "vidocq:generate: ";

    /**
     * What the pass did.
     *
     * @param processed       one entry per jar that got generated files, with its bricks and their input lines
     * @param mergedResources the union of the bricks' {@code merge} lists
     */
    record Result(List<EnrichedJars.Scanned> processed, Set<String> mergedResources) {
    }

    /**
     * What the bricks decide for one dependency jar that one of them wants or skips.
     *
     * @param bricks the bricks that select it, in their order; empty when every concerned brick skips it
     */
    record Selection(ScanSelection.Dependency dependency, DependencyCodegenSelection.Decision decision,
                     List<Brick> bricks) {
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
     * The bricks' decision for each of {@code dependencies} that one of them wants or skips, in their order: the one
     * place that selects jars for the bricks. A jar of the Vidocq runtime itself (group {@code io.vidocq} or
     * {@code io.vidocq.*}) is no candidate, nor is a jar in {@code scanExcludes} or a reactor classes directory.
     */
    static List<Selection> select(List<Brick> bricks, List<ScanSelection.Dependency> dependencies,
                                  List<String> scanExcludes) throws IOException {
        return select(bricks, dependencies, scanExcludes, null, false);
    }

    /**
     * {@link #select(List, List, List)} with each jar's decision memoized in {@code memo}
     * ({@link DependencyCodegenSelection#decide(Path, List, Path, boolean)}), so that a jar is searched once per
     * jar and hints, not on every build.
     *
     * @param memo  the memo directory, or {@code null} for none
     * @param store whether new decisions are written to it
     */
    static List<Selection> select(List<Brick> bricks, List<ScanSelection.Dependency> dependencies,
                                  List<String> scanExcludes, Path memo, boolean store) throws IOException {
        List<Selection> selections = new ArrayList<>();
        if (bricks.isEmpty()) {
            return selections;
        }
        List<DependencyCodegenHints> hints = bricks.stream().map(Brick::hints).toList();
        for (ScanSelection.Dependency dep : dependencies) {
            if (!Files.isRegularFile(dep.jar()) || isVidocqRuntime(dep) || matchesAny(scanExcludes, dep)) {
                continue;
            }
            var decision = memo == null ? DependencyCodegenSelection.decide(dep.jar(), hints)
                    : DependencyCodegenSelection.decide(dep.jar(), hints, memo, store);
            if (decision.matches().isEmpty() && decision.skips().isEmpty()) {
                continue;
            }
            List<Brick> selected = bricks.stream()
                    .filter(b -> decision.matches().stream().anyMatch(m -> m.brick() == b.hints())).toList();
            selections.add(new Selection(dep, decision, selected));
        }
        return selections;
    }

    /** The jars some brick selects, with the names of those bricks: what Vauban scans for them too. */
    static Map<Path, List<String>> bricksByJar(List<Selection> selections) {
        Map<Path, List<String>> byJar = new LinkedHashMap<>();
        for (Selection s : selections) {
            if (!s.bricks().isEmpty()) {
                byJar.put(s.dependency().jar(), s.bricks().stream().map(b -> b.hints().brick()).toList());
            }
        }
        return byJar;
    }

    /**
     * Deletes what the pass wrote into the patch directories on its last run, as {@link #WRITTEN_DIR} records it, and
     * the records: a jar no longer processed (put in {@code scanExcludes}, upgraded, no longer a candidate) must not
     * keep its old output, which the enriched copy and {@code --patch-module} would still carry. A patch directory
     * left empty goes too. What Vauban parked is never recorded, so it stays. {@code vidocq:generate} calls it first
     * on every run, before Vauban parks its classes; the pass then writes and records the current output.
     */
    static void forgetWritten(Path buildDir) throws IOException {
        Path records = buildDir.resolve(LOG_DIR).resolve(WRITTEN_DIR);
        if (!Files.isDirectory(records)) {
            return;
        }
        Path patchRoot = JpmsPatches.root(buildDir);
        try (var lists = Files.list(records)) {
            for (Path list : lists.filter(p -> p.getFileName().toString().endsWith(WRITTEN_SUFFIX)).toList()) {
                String name = list.getFileName().toString();
                Path patchDir = JpmsPatches.patchDirFor(buildDir,
                        name.substring(0, name.length() - WRITTEN_SUFFIX.length())).normalize();
                if (patchDir.startsWith(patchRoot) && !patchDir.equals(patchRoot)) {
                    for (String line : Files.readAllLines(list)) {
                        Path file = patchDir.resolve(line).normalize();
                        if (!line.isBlank() && file.startsWith(patchDir) && !file.equals(patchDir)) {
                            Files.deleteIfExists(file);
                            pruneEmptyDirs(patchRoot, file.getParent());
                        }
                    }
                }
                Files.delete(list);
            }
        }
    }

    /** Deletes {@code dir} and its parents while they are empty, up to {@code stopAt} excluded. */
    private static void pruneEmptyDirs(Path stopAt, Path dir) throws IOException {
        for (Path current = dir; current != null && current.startsWith(stopAt) && !current.equals(stopAt)
                && Files.isDirectory(current); current = current.getParent()) {
            try (var children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.delete(current);
        }
    }

    private static boolean isVidocqRuntime(ScanSelection.Dependency dep) {
        return "io.vidocq".equals(dep.groupId()) || dep.groupId().startsWith("io.vidocq.");
    }

    /**
     * Runs the pass over {@code dependencies}: {@link #select} then {@link #generate}.
     *
     * @param scanExcludes patterns of jars never processed
     */
    Result run(List<ScanSelection.Dependency> dependencies, List<Path> classpath, int release,
               List<String> scanDependencies, List<String> scanExcludes) throws MojoFailureException, IOException {
        return generate(select(bricks, dependencies, scanExcludes), classpath, coordinatesOf(dependencies), release,
                scanDependencies);
    }

    /**
     * Generates for the jars of {@code selections} that a brick selects. The problems of every jar are collected
     * first, then fail the build together, one line each.
     *
     * @param classpath            the jars' compile classpath: every dependency jar or directory of the project
     * @param classpathCoordinates that classpath as {@code groupId:artifactId:version}, for the cache key
     * @param scanDependencies     patterns naming the signed jars to process anyway
     */
    Result generate(List<Selection> selections, List<Path> classpath, List<String> classpathCoordinates, int release,
                    List<String> scanDependencies) throws MojoFailureException, IOException {
        if (bricks.isEmpty()) {
            return new Result(List.of(), Set.of());
        }
        Set<String> mergedResources = DependencyBricks.mergedResources(bricks);
        List<EnrichedJars.Scanned> processed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Selection selection : selections) {
            if (selection.bricks().isEmpty()) {
                continue;
            }
            ScanSelection.Dependency dep = selection.dependency();
            Path jar = dep.jar();
            var decision = selection.decision();
            List<Brick> selected = selection.bricks();
            String names = selected.stream().map(b -> b.hints().brick()).collect(Collectors.joining(", "));
            String file = jar.getFileName().toString();
            if (decision.signed() && !matchesAny(scanDependencies, dep)) {
                info.accept(LOG_PREFIX + file + " is signed; not processed for " + names
                        + " (name it in scanDependencies to process it)");
                continue;
            }
            String key = DependencyCodegenCache.key(jar, selected, release, classpathCoordinates);
            Optional<DependencyCodegen.Output> cached = cache.get(key);
            DependencyCodegen.Output output;
            if (cached.isPresent()) {
                output = cached.get();
            } else {
                Path log = buildDir.resolve(LOG_DIR).resolve(dep.artifactId() + ".log");
                try {
                    List<String> classes = decision.matches().stream()
                            .filter(m -> selected.stream().anyMatch(b -> b.hints() == m.brick()))
                            .flatMap(m -> m.classes().stream()).distinct().sorted().toList();
                    output = generator.generate(jar, selected, classes, classpath, release,
                            dep.groupId() + ":" + dep.artifactId(), log, display(baseDir, log));
                } catch (DependencyCodegen.Failure f) {
                    failures.addAll(f.lines());
                    continue;
                }
                cache.put(key, output);
            }
            // What describes every class of the jar would be partial, the processors having seen only the hinted
            // ones: dropped here, not before caching, so that a change of the hints needs no new cache entry.
            Set<String> wholeJar = selected.stream().flatMap(b -> b.hints().wholeJar().stream())
                    .collect(Collectors.toSet());
            if (output.files().keySet().stream().anyMatch(wholeJar::contains)) {
                Map<String, byte[]> kept = new LinkedHashMap<>(output.files());
                kept.keySet().removeAll(wholeJar);
                output = new DependencyCodegen.Output(kept, output.warnings());
            }
            output.warnings().forEach(warn);
            List<String> clashes = clashes(jar, output, mergedResources, names, dep);
            if (!clashes.isEmpty()) {
                failures.addAll(clashes);
                continue;
            }
            long classes = output.files().keySet().stream().filter(n -> n.endsWith(".class")).count();
            info.accept(LOG_PREFIX + file + " — " + names + " (" + classes
                    + (classes == 1 ? " class, " : " classes, ")
                    + (cached.isPresent() ? "cached" : "generated") + ")");
            if (output.files().isEmpty()) {
                continue;
            }
            Path patchDir = JpmsPatches.patchDirFor(buildDir, dep.artifactId());
            List<String> written = new ArrayList<>();
            for (var e : output.files().entrySet()) {
                Path target = patchDir.resolve(e.getKey()).normalize();
                if (!target.startsWith(patchDir)) {
                    throw new IOException(file + ": generated entry " + e.getKey() + " escapes " + patchDir);
                }
                // A file already there is one Vauban parked this run: the next run must not delete it.
                if (!Files.exists(target)) {
                    written.add(e.getKey());
                }
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue());
            }
            record(dep.artifactId(), written);
            processed.add(new EnrichedJars.Scanned(jar, dep.artifactId(), dep.coordinates(),
                    selected.stream().map(b -> b.hints().brick()).toList(), DependencyBricks.inputLines(selected)));
        }
        if (!failures.isEmpty()) {
            failures.forEach(error);
            throw new MojoFailureException(String.join("\n", failures));
        }
        return new Result(processed, mergedResources);
    }

    /** Adds {@code files} to what {@link #forgetWritten} deletes on the next run for {@code artifactId}. */
    private void record(String artifactId, List<String> files) throws IOException {
        if (files.isEmpty()) {
            return;
        }
        Path list = buildDir.resolve(LOG_DIR).resolve(WRITTEN_DIR).resolve(artifactId + WRITTEN_SUFFIX);
        Files.createDirectories(list.getParent());
        Files.write(list, files, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
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
                    lines.add(LOG_PREFIX + jar.getFileName() + " — " + bricks + " generated " + name
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

    /** Under the cache directory: the memoized selection decisions. */
    static Path selectionMemo(Path cacheDir) {
        return cacheDir.resolve("selection");
    }

    /** The {@code groupId:artifactId:version} of each of {@code dependencies}: the classpath of the cache key. */
    static List<String> coordinatesOf(List<ScanSelection.Dependency> dependencies) {
        return dependencies.stream().map(ScanSelection.Dependency::coordinates).toList();
    }

    /** {@code log} relative to {@code baseDir}, as a failure line shows it; absolute when they share no root. */
    static String display(Path baseDir, Path log) {
        try {
            return baseDir.relativize(log).toString();
        } catch (IllegalArgumentException _) {
            return log.toString();
        }
    }

    /** The {@code --release} of the pass: the project's {@code maven.compiler.release}, else the running JDK's. */
    static int release(String property) {
        return property == null || property.isBlank() ? Runtime.version().feature()
                : Integer.parseInt(property.strip());
    }
}
