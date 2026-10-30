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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Surefire puts on the module path only the dependencies the application module requires: a runtime dependency
 * no module requires, a JDBC driver for one, stays on the class path of the tests, and the application that
 * {@code @VidocqTest} starts never saw it (BUG-20261030-06). The goal names the runtime dependencies for it.
 */
class VidocqTestPathMojoTest {

    private static Artifact artifact(String artifactId, String scope, String type) {
        Artifact artifact = new DefaultArtifact("org.dep", artifactId, "1.0", scope, type, "",
                new DefaultArtifactHandler(type));
        if (!"pom".equals(type)) {
            artifact.setFile(new File("/m2/" + artifactId + ".jar"));
        }
        return artifact;
    }

    @Test
    void theRuntimePathHoldsTheCompileAndRuntimeJarsOnly() {
        List<Artifact> artifacts = List.of(
                artifact("core", Artifact.SCOPE_COMPILE, "jar"),
                artifact("postgresql", Artifact.SCOPE_RUNTIME, "jar"),
                artifact("servlet-api", Artifact.SCOPE_PROVIDED, "jar"),
                artifact("junit", Artifact.SCOPE_TEST, "jar"),
                artifact("bom", Artifact.SCOPE_COMPILE, "pom"));

        assertEquals(String.join(File.pathSeparator, "/m2/core.jar", "/m2/postgresql.jar"),
                VidocqTestPathMojo.runtimePath(artifacts));
    }

    @Test
    void theGoalHandsTheRuntimePathToSurefireThroughAProjectProperty() {
        MavenProject project = new MavenProject(new Model());
        Set<Artifact> artifacts = new LinkedHashSet<>(List.of(artifact("postgresql", Artifact.SCOPE_RUNTIME, "jar")));
        project.setArtifacts(artifacts);
        VidocqTestPathMojo mojo = new VidocqTestPathMojo();
        mojo.project = project;

        mojo.execute();

        assertEquals("/m2/postgresql.jar", project.getProperties().getProperty(VidocqTestPathMojo.PROPERTY));
    }
}
