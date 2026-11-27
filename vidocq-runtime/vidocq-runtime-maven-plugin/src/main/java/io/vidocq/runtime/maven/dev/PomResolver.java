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
package io.vidocq.runtime.maven.dev;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the {@code pom.xml} of a {@code vidocq:dev} session again, its compile and runtime dependencies resolved as
 * Maven resolves them for the goal ({@code requiresDependencyResolution = RUNTIME}), with Maven's own project builder
 * (Vidocq/vidocq#799). A pom that does not build, or a dependency that does not resolve, is a failure.
 */
final class PomResolver implements PomChange.Resolver {

    private final ProjectBuilder builder;
    private final ProjectBuildingRequest base;
    private final File pom;

    /**
     * @param builder Maven's project builder
     * @param base    the session's request: its repositories, profiles and properties; copied at each resolution
     * @param pom     the project's {@code pom.xml}
     */
    PomResolver(ProjectBuilder builder, ProjectBuildingRequest base, File pom) {
        this.builder = builder;
        this.base = base;
        this.pom = pom;
    }

    @Override
    public MavenProject resolve() throws PomChange.ResolutionFailedException {
        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(base);
        request.setResolveDependencies(true);
        request.setProject(null);
        ProjectBuildingResult result;
        try {
            result = builder.build(pom, request);
        } catch (ProjectBuildingException e) {
            throw new PomChange.ResolutionFailedException(e.getMessage(), e);
        }
        DependencyResolutionResult resolution = result.getDependencyResolutionResult();
        if (resolution != null && !resolution.getCollectionErrors().isEmpty()) {
            Exception error = resolution.getCollectionErrors().getFirst();
            throw new PomChange.ResolutionFailedException(error.getMessage(), error);
        }
        if (resolution != null && !resolution.getUnresolvedDependencies().isEmpty()) {
            throw new PomChange.ResolutionFailedException("cannot resolve "
                    + resolution.getUnresolvedDependencies().getFirst().getArtifact(), null);
        }
        MavenProject resolved = result.getProject();
        resolved.setArtifacts(runtimeArtifacts(resolved.getArtifacts()));
        return resolved;
    }

    /** What {@code requiresDependencyResolution = RUNTIME} gives the goal: the compile and runtime scopes. */
    static Set<Artifact> runtimeArtifacts(Collection<Artifact> artifacts) {
        ScopeArtifactFilter runtime = new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME);
        Set<Artifact> kept = new LinkedHashSet<>();
        for (Artifact artifact : artifacts) {
            if (runtime.include(artifact)) {
                kept.add(artifact);
            }
        }
        return kept;
    }
}
