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
package io.vidocq.runtime.maven.depgen;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds the codegen bundles of a project on the compiler's annotationProcessorPaths. */
public final class CodegenBundles {

    private CodegenBundles() {
    }

    /**
     * The bricks of the project: each processor path whose own artifact holds a hints file. A path without a
     * version takes it from the project's dependencyManagement (same g:a, type jar, or the path's type); a path
     * without a version anywhere fails naming it. A {@code type=pom} path is skipped (IntelliJ twin, #237).
     */
    public static List<Brick> of(MavenProject project, ArtifactResolver resolver) throws MojoExecutionException {
        List<Brick> bricks = new ArrayList<>();
        for (ProcessorPath path : ProcessorPaths.of(project)) {
            if ("pom".equals(path.type())) continue;
            String version = path.version() != null ? path.version() : managedVersion(project, path);
            if (version == null) {
                throw new MojoExecutionException("annotationProcessorPaths entry " + path.groupId() + ":"
                        + path.artifactId() + " has no version and the project's dependencyManagement has none");
            }
            String coordinates = path.groupId() + ":" + path.artifactId() + ":" + version;
            try {
                Path own = resolver.resolve(path.groupId(), path.artifactId(), version, path.type(),
                        path.classifier());
                var hints = DependencyCodegenHints.read(own);
                if (hints.isEmpty()) continue;
                bricks.add(new Brick(hints.get(), coordinates, resolver.resolveWithDependencies(path.groupId(),
                        path.artifactId(), version, path.type(), path.classifier())));
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot read the codegen bundle " + coordinates + ": "
                        + e.getMessage(), e);
            } catch (IllegalArgumentException e) {
                throw new MojoExecutionException("The codegen bundle " + coordinates + " declares malformed hints: "
                        + e.getMessage(), e);
            }
        }
        return bricks;
    }

    private static String managedVersion(MavenProject project, ProcessorPath path) {
        if (project.getDependencyManagement() == null) return null;
        for (Dependency d : project.getDependencyManagement().getDependencies()) {
            if (path.groupId().equals(d.getGroupId()) && path.artifactId().equals(d.getArtifactId())
                    && d.getVersion() != null
                    && (path.type().equals(d.getType()) || "jar".equals(d.getType()))) {
                return d.getVersion();
            }
        }
        return null;
    }
}
