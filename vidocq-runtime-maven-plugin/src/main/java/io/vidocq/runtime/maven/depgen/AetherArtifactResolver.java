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

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** {@link ArtifactResolver} over the Aether objects Maven injects into a mojo. */
public final class AetherArtifactResolver implements ArtifactResolver {

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    public AetherArtifactResolver(RepositorySystem system, RepositorySystemSession session,
                           List<RemoteRepository> repositories) {
        this.system = system;
        this.session = session;
        this.repositories = repositories;
    }

    @Override
    public Path resolve(String groupId, String artifactId, String version, String type, String classifier)
            throws IOException {
        try {
            ArtifactRequest request = new ArtifactRequest(artifact(groupId, artifactId, version, type, classifier),
                    repositories, null);
            return system.resolveArtifact(session, request).getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            throw new IOException("Cannot resolve " + groupId + ":" + artifactId + ":" + version + ": "
                    + e.getMessage(), e);
        }
    }

    @Override
    public List<Path> resolveWithDependencies(String groupId, String artifactId, String version, String type,
                                              String classifier) throws IOException {
        try {
            Dependency root = new Dependency(artifact(groupId, artifactId, version, type, classifier),
                    JavaScopes.RUNTIME);
            DependencyRequest request = new DependencyRequest(new CollectRequest(root, repositories),
                    DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
            return system.resolveDependencies(session, request).getArtifactResults().stream()
                    .map(ArtifactResult::getArtifact).map(a -> a.getFile().toPath()).toList();
        } catch (DependencyResolutionException e) {
            throw new IOException("Cannot resolve " + groupId + ":" + artifactId + ":" + version
                    + " and its dependencies: " + e.getMessage(), e);
        }
    }

    private static DefaultArtifact artifact(String groupId, String artifactId, String version, String type,
                                            String classifier) {
        return new DefaultArtifact(groupId, artifactId, classifier == null ? "" : classifier, type, version);
    }
}
