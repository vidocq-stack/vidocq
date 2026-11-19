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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Finds the codegen bundles of a project on the compiler's annotationProcessorPaths. */
public final class CodegenBundles {

    private CodegenBundles() {
    }

    /**
     * The bricks of the project: each processor path whose own artifact holds a hints file, and each jar of the
     * closure of a processor path that holds one (Cassini's processor declares its own, next to the bundle that
     * brings it). A jar of a closure comes before the bundle that brings it, and once for all the bundles that do, so
     * that its processors are attributed to it. A path without a version takes it from the project's
     * dependencyManagement (same g:a, type jar, or the path's type); a path without a version anywhere fails naming
     * it. A {@code type=pom} path is skipped (IntelliJ twin, #237).
     */
    public static List<Brick> of(MavenProject project, ArtifactResolver resolver) throws MojoExecutionException {
        List<Brick> bricks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ProcessorPath path : ProcessorPaths.of(project)) {
            if ("pom".equals(path.type())) continue;
            String version = path.version() != null ? path.version() : managedVersion(project, path);
            if (version == null) {
                throw new MojoExecutionException("annotationProcessorPaths entry " + path.groupId() + ":"
                        + path.artifactId() + " has no version and the project's dependencyManagement has none");
            }
            bricks.addAll(bricks(path, version, resolver, seen));
        }
        return bricks;
    }

    /** The bricks of one versioned processor path: those of its closure, then its own. */
    private static List<Brick> bricks(ProcessorPath path, String version, ArtifactResolver resolver,
                                      Set<String> seen) throws MojoExecutionException {
        String coordinates = path.groupId() + ":" + path.artifactId() + ":" + version;
        try {
            List<ArtifactResolver.Resolved> closure = resolver.resolveClosure(path.groupId(), path.artifactId(),
                    version, path.type(), path.classifier());
            List<Brick> bricks = new ArrayList<>();
            Brick own = null;
            for (ArtifactResolver.Resolved jar : closure) {
                String jarCoordinates = jar.groupId() + ":" + jar.artifactId() + ":" + jar.version();
                boolean isOwn = jar.groupId().equals(path.groupId()) && jar.artifactId().equals(path.artifactId());
                // A jar of another bundle's closure is looked at once; the bundle's own jar always.
                boolean unseen = seen.add(jarCoordinates);
                Optional<DependencyCodegenHints> hints = isOwn || unseen
                        ? readHints(jar.file(), jarCoordinates) : Optional.empty();
                if (hints.isPresent() && isOwn) {
                    own = new Brick(hints.get(), coordinates, closure.stream()
                            .map(ArtifactResolver.Resolved::file).toList());
                } else if (hints.isPresent()) {
                    bricks.add(new Brick(hints.get(), jarCoordinates, resolver.resolveWithDependencies(
                            jar.groupId(), jar.artifactId(), jar.version(), "jar", jar.classifier())));
                }
            }
            if (own != null) bricks.add(own);
            return bricks;
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read the codegen bundle " + coordinates + ": "
                    + e.getMessage(), e);
        }
    }

    private static Optional<DependencyCodegenHints> readHints(Path jar, String coordinates)
            throws MojoExecutionException, IOException {
        try {
            return DependencyCodegenHints.read(jar);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("The codegen bundle " + coordinates + " declares malformed hints: "
                    + e.getMessage(), e);
        }
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
