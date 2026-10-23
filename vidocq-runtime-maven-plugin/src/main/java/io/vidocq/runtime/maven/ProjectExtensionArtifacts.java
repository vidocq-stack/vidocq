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

import io.vidocq.runtime.tooling.ExtensionCoordinate;
import io.vidocq.runtime.tooling.ExtensionJars;
import io.vidocq.runtime.tooling.KnownExtensions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Vidocq extensions a project runs with: its resolved artifacts whose module provides the extension SPI, the
 * ones it declares and the ones they pull in — what the CLI finds by running Maven (Vidocq/vidocq#314).
 */
final class ProjectExtensionArtifacts {

    /**
     * An extension of the project.
     *
     * @param id       the catalog id when known, otherwise the artifactId
     * @param artifact {@code groupId:artifactId:version}
     * @param direct   whether the project's pom declares it itself
     */
    record Installed(String id, String artifact, boolean direct) {}

    private ProjectExtensionArtifacts() {}

    /** The extensions among the project's resolved artifacts, in resolution order. */
    static List<Installed> of(MavenProject project) {
        Set<String> declared = project.getDependencies().stream()
                .map(ProjectExtensionArtifacts::key)
                .collect(Collectors.toSet());
        return project.getArtifacts().stream()
                .filter(ProjectExtensionArtifacts::isExtension)
                .map(a -> new Installed(
                        KnownExtensions.idOf(new ExtensionCoordinate(a.getGroupId(), a.getArtifactId())),
                        a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion(),
                        declared.contains(a.getGroupId() + ":" + a.getArtifactId())))
                .toList();
    }

    private static String key(Dependency d) {
        return d.getGroupId() + ":" + d.getArtifactId();
    }

    /** Whether the resolved {@code artifact}, a jar or a reactor module's classes, is a Vidocq extension. */
    private static boolean isExtension(Artifact artifact) {
        return artifact.getFile() != null && ExtensionJars.providesExtension(artifact.getFile().toPath());
    }
}
