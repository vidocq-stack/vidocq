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
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#799: the pom of a {@code vidocq:dev} session read again, with Maven's project builder. */
class PomResolverTest {

    private static final File POM = new File("pom.xml");

    @Test
    void theProjectComesWithItsCompileAndRuntimeDependenciesOnly() throws Exception {
        MavenProject built = new MavenProject(new Model());
        built.setArtifacts(new LinkedHashSet<>(List.of(artifact("core", "compile"), artifact("json", "runtime"),
                artifact("junit", "test"), artifact("api", "provided"))));
        AtomicReference<ProjectBuildingRequest> asked = new AtomicReference<>();
        ProjectBuildingRequest base = new DefaultProjectBuildingRequest();

        MavenProject resolved = new PomResolver(builder(asked, built, resolution(List.of(), List.of())), base, POM)
                .resolve();

        assertSame(built, resolved);
        assertEquals(Set.of("core", "json"), resolved.getArtifacts().stream().map(Artifact::getArtifactId)
                .collect(Collectors.toSet()));
        assertTrue(asked.get().isResolveDependencies(), "the dependencies are resolved");
        assertNotSame(base, asked.get(), "the session's request is copied, never changed");
    }

    @Test
    void aPomThatDoesNotBuildIsAFailure() {
        ProjectBuilder broken = buildingFiles((pom, request) -> {
            throw new ProjectBuildingException("com.example:app", "Non-parseable POM", pom);
        });
        PomChange.ResolutionFailedException failed = assertThrows(PomChange.ResolutionFailedException.class,
                () -> new PomResolver(broken, new DefaultProjectBuildingRequest(), POM).resolve());
        assertTrue(failed.getMessage().contains("Non-parseable POM"), failed.getMessage());
    }

    @Test
    void aDependencyThatCannotBeCollectedOrResolvedIsAFailure() {
        MavenProject built = new MavenProject(new Model());
        PomChange.ResolutionFailedException collect = assertThrows(PomChange.ResolutionFailedException.class,
                () -> new PomResolver(builder(new AtomicReference<>(), built, resolution(
                        List.of(new IllegalStateException("Could not find artifact com.example:nope:1.0")),
                        List.of())), new DefaultProjectBuildingRequest(), POM).resolve());
        assertEquals("Could not find artifact com.example:nope:1.0", collect.getMessage());

        Dependency missing = new Dependency(new org.eclipse.aether.artifact.DefaultArtifact("com.example:nope:1.0"),
                "compile");
        PomChange.ResolutionFailedException resolve = assertThrows(PomChange.ResolutionFailedException.class,
                () -> new PomResolver(builder(new AtomicReference<>(), built, resolution(List.of(),
                        List.of(missing))), new DefaultProjectBuildingRequest(), POM).resolve());
        assertEquals("cannot resolve com.example:nope:jar:1.0", resolve.getMessage());
    }

    private static Artifact artifact(String artifactId, String scope) {
        Artifact artifact = new DefaultArtifact("com.example", artifactId, "1.0", scope, "jar", "",
                new DefaultArtifactHandler("jar"));
        artifact.setFile(new File(artifactId + ".jar"));
        return artifact;
    }

    private static ProjectBuilder builder(AtomicReference<ProjectBuildingRequest> asked, MavenProject project,
            DependencyResolutionResult resolution) {
        return buildingFiles((pom, request) -> {
            asked.set(request);
            return new ProjectBuildingResult() {
                @Override
                public String getProjectId() {
                    return "com.example:app";
                }

                @Override
                public File getPomFile() {
                    return pom;
                }

                @Override
                public MavenProject getProject() {
                    return project;
                }

                @Override
                public List<ModelProblem> getProblems() {
                    return List.of();
                }

                @Override
                public DependencyResolutionResult getDependencyResolutionResult() {
                    return resolution;
                }
            };
        });
    }

    /** What the fake builder does with a pom file. */
    @FunctionalInterface
    private interface BuildFile {
        ProjectBuildingResult build(File pom, ProjectBuildingRequest request) throws ProjectBuildingException;
    }

    /**
     * A project builder that builds a file only; a proxy, so that the test names none of the deprecated types of the
     * other methods.
     */
    private static ProjectBuilder buildingFiles(BuildFile build) {
        return (ProjectBuilder) Proxy.newProxyInstance(PomResolverTest.class.getClassLoader(),
                new Class<?>[] {ProjectBuilder.class}, (proxy, method, args) -> {
                    if ("build".equals(method.getName()) && args.length == 2 && args[0] instanceof File pom) {
                        return build.build(pom, (ProjectBuildingRequest) args[1]);
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
    }

    private static DependencyResolutionResult resolution(List<Exception> collectionErrors, List<Dependency> unresolved) {
        return new DependencyResolutionResult() {
            @Override
            public DependencyNode getDependencyGraph() {
                return null;
            }

            @Override
            public List<Dependency> getDependencies() {
                return List.of();
            }

            @Override
            public List<Dependency> getResolvedDependencies() {
                return List.of();
            }

            @Override
            public List<Dependency> getUnresolvedDependencies() {
                return unresolved;
            }

            @Override
            public List<Exception> getCollectionErrors() {
                return collectionErrors;
            }

            @Override
            public List<Exception> getResolutionErrors(Dependency dependency) {
                return List.of();
            }
        };
    }
}
