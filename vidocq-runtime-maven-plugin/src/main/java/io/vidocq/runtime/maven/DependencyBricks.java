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

import io.vidocq.runtime.maven.depgen.AetherArtifactResolver;
import io.vidocq.runtime.maven.depgen.Brick;
import io.vidocq.runtime.maven.depgen.CodegenBundles;
import io.vidocq.runtime.maven.depgen.DependencyCodegenCache;
import io.vidocq.vauban.maven.modularize.ModularizedJars;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What {@code vidocq:generate}, {@code vidocq:package} and {@code vidocq:jlink} must agree on about the bricks that
 * processed a dependency jar: their input lines, the resources they merge and the modules their generated classes
 * require. Generate computes them from the jars the bricks selected; packaging reads the bricks back from the
 * {@code Vidocq-Enriched-By} of the copy generate wrote, so it rebuilds that copy from the same inputs without
 * running the processors again.
 */
final class DependencyBricks {

    /** The project's bricks, looked up only when a copy needs them. */
    @FunctionalInterface
    interface Source {
        List<Brick> get() throws MojoExecutionException;
    }

    private DependencyBricks() {
    }

    /** The project's bricks: the codegen bundles of its {@code annotationProcessorPaths} that declare hints. */
    static List<Brick> of(MavenProject project, RepositorySystem system, RepositorySystemSession session,
                          List<RemoteRepository> repositories) throws MojoExecutionException {
        return CodegenBundles.of(project, new AetherArtifactResolver(system, session,
                repositories == null ? List.of() : repositories));
    }

    /** {@code brick <name> <bundle coordinates> sha256:<processor classpath digest>}. */
    static String inputLine(Brick brick) throws IOException {
        return "brick " + brick.hints().brick() + " " + brick.coordinates() + " sha256:"
                + DependencyCodegenCache.classpathDigest(brick.processorPath());
    }

    /** The input lines of {@code bricks}, sorted. */
    static List<String> inputLines(Collection<Brick> bricks) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Brick brick : bricks) {
            lines.add(inputLine(brick));
        }
        Collections.sort(lines);
        return List.copyOf(lines);
    }

    /** The union of the bricks' {@code merge} lists. */
    static Set<String> mergedResources(Collection<Brick> bricks) {
        Set<String> merged = new LinkedHashSet<>();
        bricks.forEach(b -> merged.addAll(b.hints().merge()));
        return Collections.unmodifiableSet(merged);
    }

    /**
     * The modules, among {@code dependencies}, that hold any of the given packages. Each dependency is read as a
     * module on its own, so that one that is not a valid module (an automatic name that cannot be derived, a broken
     * descriptor) loses only its own packages; {@code warn} names it.
     */
    static Function<Set<String>, Set<String>> modulesOfPackages(Collection<Path> dependencies,
                                                               Consumer<String> warn) {
        Map<String, String> owners = new HashMap<>();
        for (Path entry : dependencies) {
            boolean module = Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")
                    || Files.isRegularFile(entry.resolve(EnrichedJars.MODULE_INFO));
            if (!module) {
                continue;
            }
            try {
                for (ModuleReference ref : ModuleFinder.of(entry).findAll()) {
                    ref.descriptor().packages().forEach(p -> owners.putIfAbsent(p, ref.descriptor().name()));
                }
            } catch (FindException e) {
                warn.accept(entry.getFileName() + " is not a valid module (" + firstLine(e)
                        + "): a copy whose generated classes use its packages does not require it");
            }
        }
        return packages -> packages.stream().map(owners::get).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The first line of the deepest message of {@code e}. */
    private static String firstLine(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = String.valueOf(cause.getMessage());
        int eol = message.indexOf('\n');
        return (eol < 0 ? message : message.substring(0, eol)).strip();
    }

    /**
     * {@code dep} with the bricks its enriched copy names in {@code Vidocq-Enriched-By} and their input lines, as
     * {@code vidocq:generate} gave them. {@code dep} as it is when there is no such copy, or when a brick it names is
     * no longer among the project's: the copy is then kept as generate wrote it.
     */
    static EnrichedJars.Scanned forPackaging(Path buildDir, EnrichedJars.Scanned dep, Source bricks)
            throws IOException, MojoExecutionException {
        List<String> names = EnrichedJars.enrichedBy(buildDir, dep.originalJar());
        if (names.isEmpty()) {
            return dep;
        }
        List<Brick> all = bricks.get();
        List<Brick> used = new ArrayList<>();
        for (String name : names) {
            Optional<Brick> brick = all.stream().filter(b -> b.hints().brick().equals(name)).findFirst();
            if (brick.isEmpty()) {
                return dep;
            }
            used.add(brick.get());
        }
        return new EnrichedJars.Scanned(dep.originalJar(), dep.artifactId(), dep.coordinates(), names,
                inputLines(used));
    }

    /** The {@link Packaging} of {@code project}, over its dependency jars and directories. */
    static Packaging packaging(MavenProject project, Path buildDir, RepositorySystem system,
                               RepositorySystemSession session, List<RemoteRepository> repositories,
                               Consumer<String> warn) {
        List<Path> dependencies = new ArrayList<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && ScanSelection.isJarOrDirectory(artifact.getFile().toPath())) {
                dependencies.add(artifact.getFile().toPath());
            }
        }
        return new Packaging(buildDir, () -> of(project, system, session, repositories), dependencies, warn);
    }

    /**
     * The rebase {@code vidocq:package} and {@code vidocq:jlink} run on each dependency after
     * {@code vauban:modularize}: brick-aware for a copy the bricks enriched. The bricks and the modules of the
     * dependencies are looked up once, on the first such copy with a modularized jar to rebase on.
     */
    static final class Packaging {

        private final Path buildDir;
        private final Source source;
        private final Collection<Path> dependencies;
        private final Consumer<String> warn;
        private List<Brick> bricks;
        private Function<Set<String>, Set<String>> modulesOfPackages;

        Packaging(Path buildDir, Source source, Collection<Path> dependencies, Consumer<String> warn) {
            this.buildDir = buildDir;
            this.source = source;
            this.dependencies = dependencies;
            this.warn = warn;
        }

        void rebase(EnrichedJars.Scanned dep, Consumer<String> info) throws IOException, MojoExecutionException {
            if (!ModularizedJars.isModularized(buildDir, dep.originalJar())) {
                return; // nothing to rebase on, whatever the bricks
            }
            EnrichedJars.Scanned scanned = forPackaging(buildDir, dep, this::bricks);
            if (scanned.bricks().isEmpty()) {
                EnrichedJars.rebaseOnModularized(buildDir, dep, info);
                return;
            }
            if (modulesOfPackages == null) {
                modulesOfPackages = modulesOfPackages(dependencies, warn);
            }
            EnrichedJars.rebaseOnModularized(buildDir, scanned, mergedResources(bricks()), modulesOfPackages, info);
        }

        private List<Brick> bricks() throws MojoExecutionException {
            if (bricks == null) {
                bricks = source.get();
            }
            return bricks;
        }
    }
}
