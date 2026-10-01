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

import io.vidocq.vauban.maven.modularize.ModularizedJars;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        List<Path> entries = new ArrayList<>();
        if (!layerMode) {
            entries.add(classesDir);
        }
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
                entries.add(resolved);
            }
        }
        return entries;
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
