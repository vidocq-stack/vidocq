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
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
     * The module path of the child JVM.
     *
     * @param project     the project being run
     * @param buildDir    {@code target/}, where {@code vauban:modularize} writes its copies
     * @param classesDir  {@code target/classes}
     * @param layerMode   universal-loader mode: the application classes travel through
     *                    {@code -Dvidocq.app.path} instead, since a module must not be on both paths
     * @param modularized called with each dependency jar that a modularized copy replaces
     */
    public static List<Path> modulePath(MavenProject project, Path buildDir, Path classesDir, boolean layerMode,
                                        Consumer<Path> modularized) {
        List<Path> entries = new ArrayList<>();
        if (!layerMode) {
            entries.add(classesDir);
        }
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && "jar".equals(artifact.getType())) {
                Path jar = artifact.getFile().toPath();
                Path resolved = ModularizedJars.resolve(buildDir, jar);
                if (!resolved.equals(jar)) {
                    modularized.accept(jar);
                }
                entries.add(resolved);
            }
        }
        return entries;
    }

    /** The application archives of the universal-loader mode: the project's own build output. */
    public static List<Path> appPath(Path classesDir, boolean layerMode) {
        return layerMode ? List.of(classesDir) : List.of();
    }

    /**
     * The {@code --patch-module} options that re-attach the classes {@code vidocq:generate} parked in
     * {@code target/vidocq-patches/} to the dependency jar they belong to. Empty when nothing was parked.
     *
     * <p>The jars named here are the ones {@link #modulePath} puts on the path — a modularized copy when
     * there is one — otherwise {@code --patch-module} would target a jar that is not on the path.
     */
    public static List<String> patchModuleArgs(MavenProject project, Path buildDir) throws IOException {
        Map<String, Path> jarsByArtifactId = new HashMap<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null) {
                jarsByArtifactId.put(artifact.getArtifactId(),
                        ModularizedJars.resolve(buildDir, artifact.getFile().toPath()));
            }
        }
        return JpmsPatches.patchModuleArgs(buildDir, jarsByArtifactId);
    }
}
