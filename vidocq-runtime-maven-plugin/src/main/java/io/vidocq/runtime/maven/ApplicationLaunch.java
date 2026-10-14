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

import io.vidocq.runtime.core.LayerSplit;
import io.vidocq.vauban.maven.modularize.ModularizedJars;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * What a forked Vidocq JVM is launched against: the module path, the application path and the
 * {@code --patch-module} options of the classes {@code vidocq:generate} parked for scanned dependencies.
 *
 * <p>Shared by {@code vidocq:dev} and {@code vidocq:run}, so that a run from an IDE, a run from the
 * command line and a dev-mode respawn all fork the same JVM. The layout matches what
 * {@link VidocqPackageMojo} ships in {@code lib/}: the freshly compiled project classes plus every
 * resolved runtime/compile artifact, each replaced by its modularized copy when
 * {@code vauban:modularize} produced one.
 */
public final class ApplicationLaunch {

    private ApplicationLaunch() {
    }

    /**
     * The module path of the child JVM, keeping every declared jar — including one marked
     * {@link DevOnlyJars#MANIFEST_ENTRY dev-only}: {@code vidocq:dev} keeps a declared console (Vidocq/vidocq#143).
     *
     * @param project     the project being run
     * @param buildDir    {@code target/}, where {@code vauban:modularize} writes its copies
     * @param classesDir  {@code target/classes}
     * @param layerMode   universal-loader mode: the application classes travel through
     *                    {@code -Dvidocq.app.path} instead, since a module must not be on both paths
     * @param modularized called with each dependency jar that a copy replaces: enriched by {@code vidocq:generate}
     *                    or modularized by {@code vauban:modularize}
     */
    public static List<Path> modulePath(MavenProject project, Path buildDir, Path classesDir, boolean layerMode,
                                        Consumer<Path> modularized) {
        return modulePath(project, buildDir, classesDir, layerMode, modularized, false, id -> { });
    }

    /**
     * The module path of the child JVM, optionally dropping every jar marked
     * {@link DevOnlyJars#MANIFEST_ENTRY dev-only} (Vidocq/vidocq#143): no binary produced by
     * {@code vidocq:run}, {@code vidocq:package} or {@code vidocq:jlink} may carry a development tool, even
     * when the project declares it as a dependency.
     *
     * @param project     the project being run
     * @param buildDir    {@code target/}, where {@code vauban:modularize} writes its copies
     * @param classesDir  {@code target/classes}
     * @param layerMode   universal-loader mode: the application classes travel through
     *                    {@code -Dvidocq.app.path} instead, since a module must not be on both paths
     * @param modularized called with each dependency jar that a copy replaces: enriched by {@code vidocq:generate}
     *                    or modularized by {@code vauban:modularize}
     * @param dropDevOnly when {@code true}, an artifact whose jar is dev-only is left off the path
     * @param dropped     called with the artifact id of each dev-only jar that was dropped
     */
    public static List<Path> modulePath(MavenProject project, Path buildDir, Path classesDir, boolean layerMode,
                                        Consumer<Path> modularized, boolean dropDevOnly, Consumer<String> dropped) {
        List<Path> paths = new ArrayList<>();
        if (!layerMode) {
            paths.add(classesDir);
        }
        entries(project, buildDir, modularized, dropDevOnly, dropped).forEach(e -> paths.add(e.path()));
        return paths;
    }

    /** Why a jar with classes parked for {@code --patch-module} stays in the parent layer. */
    public static final String PATCHED_REASON = "classes parked by vidocq:generate need --patch-module";

    /**
     * The line vidocq:run, vidocq:dev and vidocq:package log under {@code all}: how many libraries the application
     * layer holds and how many modules a pin keeps in the parent layer.
     */
    public static String layerSummary(int libraries, int pinned) {
        return "application layer: " + libraries + (libraries == 1 ? " library, " : " libraries, ") + pinned
                + " kept in the parent layer by a pin";
    }

    /**
     * The system properties vidocq:run and vidocq:dev hand to the application under {@code all}: the mode, which the
     * runtime reads back (the dev reload's thread warning), and the pinned modules when there are some.
     */
    public static Map<String, String> layerProperties(Map<String, String> pinned) {
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        properties.put(LayerSplit.MODE_PROPERTY, "all");
        if (!pinned.isEmpty()) {
            properties.put(LayerSplit.PINNED_PROPERTY, LayerSplit.encodePinned(pinned));
        }
        return properties;
    }

    /** A launch split by {@link LayerSplit}: the module path, the application path, the pinned modules. */
    public record Layered(List<Path> modulePath, List<Path> appPath, Map<String, String> pinned) {}

    private record Entry(Artifact artifact, Path path) {}

    private static List<Entry> entries(MavenProject project, Path buildDir, Consumer<Path> modularized,
                                       boolean dropDevOnly, Consumer<String> dropped) {
        List<Entry> entries = new ArrayList<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && "jar".equals(artifact.getType())) {
                Path jar = artifact.getFile().toPath();
                if (dropDevOnly && DevOnlyJars.isDevOnly(jar)) {
                    dropped.accept(artifact.getArtifactId());
                    continue;
                }
                Path resolved = EnrichedJars.resolve(buildDir, jar);
                if (!resolved.equals(jar)) {
                    modularized.accept(jar);
                }
                entries.add(new Entry(artifact, resolved));
            }
        }
        return entries;
    }

    /**
     * The launch of {@code vidocq.app.layer=all}: the runtime's closure on the module path, the project's classes
     * and every other dependency on the application path (Vidocq/vidocq#247).
     *
     * <p>A kept automatic module's own dependencies follow it through {@link #declaredReads}, which has a limit
     * on dependencies shared with an application library: pin them with {@code vidocq.app.layer.keep}.
     *
     * @param keepPatterns the patterns of {@code vidocq.app.layer.keep}
     * @throws MojoExecutionException when a jar has no readable module, or a pin reads the application
     */
    public static Layered layered(MavenProject project, Path buildDir, Path classesDir, Consumer<Path> modularized,
                                  boolean dropDevOnly, Consumer<String> dropped, List<String> keepPatterns)
            throws MojoExecutionException {
        List<LayerSplit.Candidate> candidates = new ArrayList<>();
        List<Artifact> artifacts = new ArrayList<>();
        Map<String, String> moduleByCoordinates = new LinkedHashMap<>();
        Map<String, String> forced = new LinkedHashMap<>();
        for (Entry entry : entries(project, buildDir, modularized, dropDevOnly, dropped)) {
            Artifact a = entry.artifact();
            LayerSplit.Candidate candidate;
            try {
                candidate = LayerSplit.candidate(entry.path(), a.getGroupId() + ":" + a.getArtifactId());
            } catch (IOException e) {
                throw new MojoExecutionException("vidocq.app.layer=all reads the module of every dependency: "
                        + e.getMessage() + ". Give the jar an Automatic-Module-Name, or use vidocq.app.layer=classes.",
                        e);
            }
            candidates.add(candidate);
            artifacts.add(a);
            moduleByCoordinates.put(a.getGroupId() + ":" + a.getArtifactId(), candidate.descriptor().name());
            if (!EnrichedJars.isEnriched(buildDir, a.getFile().toPath())
                    && Files.isDirectory(JpmsPatches.patchDirFor(buildDir, a.getArtifactId()))) {
                forced.put(candidate.descriptor().name(), PATCHED_REASON);
            }
        }
        LayerSplit.Result split;
        try {
            split = LayerSplit.split(candidates, applicationModules(classesDir), keepPatterns, forced,
                    declaredReads(artifacts, moduleByCoordinates));
        } catch (IllegalStateException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        List<Path> appPath = new ArrayList<>();
        appPath.add(classesDir);
        appPath.addAll(split.applicationPaths());
        return new Layered(new ArrayList<>(split.parentPaths()), appPath, split.pinned());
    }

    /**
     * What each module depends on according to Maven: from an artifact's dependency trail, its direct parent
     * reads it. An automatic module's descriptor names no dependency, so {@link LayerSplit} needs this to keep the
     * libraries a kept automatic module uses beside it.
     *
     * <p>Limit: Maven's dependency trails record one path per artifact. When Maven mediates a dependency shared
     * with an application library through a nearer path, the kept module's read of it is not seen, and that
     * dependency lands in the application layer: pin it with {@code vidocq.app.layer.keep}.
     *
     * @param artifacts           the jars of the launch
     * @param moduleByCoordinates the module name of each {@code groupId:artifactId}
     * @return module name to the names of the modules it reads
     */
    static Map<String, Set<String>> declaredReads(List<Artifact> artifacts, Map<String, String> moduleByCoordinates) {
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        for (Artifact a : artifacts) {
            List<String> trail = a.getDependencyTrail();
            String self = moduleByCoordinates.get(a.getGroupId() + ":" + a.getArtifactId());
            if (trail == null || trail.size() < 3 || self == null) {
                continue;
            }
            // The first element is the project; the last is the artifact itself; its dependent is just before.
            String[] parent = trail.get(trail.size() - 2).split(":");
            if (parent.length < 2) {
                continue;
            }
            String parentModule = moduleByCoordinates.get(parent[0] + ":" + parent[1]);
            if (parentModule != null) {
                reads.computeIfAbsent(parentModule, k -> new LinkedHashSet<>()).add(self);
            }
        }
        return reads;
    }

    /** The project's own module name, when its classes have a descriptor. */
    private static Set<String> applicationModules(Path classesDir) {
        if (!Files.isRegularFile(classesDir.resolve("module-info.class"))) {
            return Set.of();
        }
        return ModuleFinder.of(classesDir).findAll().stream().map(r -> r.descriptor().name())
                .collect(Collectors.toSet());
    }

    /**
     * The artifacts whose jar {@link #modulePath} keeps with the same {@code dropDevOnly}: what a later step that
     * adds jars of its own (the dev services under {@code vidocq:run}) must treat as already on the path. A dropped
     * dev-only artifact is not among them, so that step adds its own copy back.
     */
    public static List<Artifact> keptArtifacts(Collection<Artifact> artifacts, boolean dropDevOnly) {
        List<Artifact> kept = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            if (dropDevOnly && artifact.getFile() != null && "jar".equals(artifact.getType())
                    && DevOnlyJars.isDevOnly(artifact.getFile().toPath())) {
                continue;
            }
            kept.add(artifact);
        }
        return kept;
    }

    /** The application archives of the universal-loader mode: the project's own build output. */
    public static List<Path> appPath(Path classesDir, boolean layerMode) {
        return layerMode ? List.of(classesDir) : List.of();
    }

    /**
     * Everything the application's classes come from once launched: the module path as {@link #modulePath} built
     * it, plus the classes directory, which layer mode keeps off it — once. What a dev service looks into to learn
     * which driver the application has (spec 2026-09-29-devservice-postgres-kind §5); taken before the dev tools
     * join the module path.
     */
    public static List<Path> classpathOf(List<Path> modulePath, Path classesDir) {
        List<Path> entries = new ArrayList<>(modulePath);
        if (!entries.contains(classesDir)) {
            entries.add(classesDir);
        }
        return entries;
    }

    /**
     * The {@code --patch-module} options that re-attach the classes {@code vidocq:generate} parked in
     * {@code target/vidocq-patches/} to the dependency jar they belong to. Empty when nothing was parked.
     *
     * <p>The jars named here are the ones {@link #modulePath} puts on the path — a modularized copy when
     * there is one — otherwise {@code --patch-module} would target a jar that is not on the path. A jar with an
     * enriched copy is left out: the copy carries the classes.
     */
    public static List<String> patchModuleArgs(MavenProject project, Path buildDir) throws IOException {
        Map<String, Path> jarsByArtifactId = new HashMap<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && !EnrichedJars.isEnriched(buildDir, artifact.getFile().toPath())) {
                jarsByArtifactId.put(artifact.getArtifactId(),
                        ModularizedJars.resolve(buildDir, artifact.getFile().toPath()));
            }
        }
        return JpmsPatches.patchModuleArgs(buildDir, jarsByArtifactId);
    }
}
