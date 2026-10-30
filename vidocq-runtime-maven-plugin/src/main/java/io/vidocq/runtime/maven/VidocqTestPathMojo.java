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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Names the runtime dependencies of the application for the application {@code @VidocqTest} starts, in the project
 * property {@value #PROPERTY}, which {@code vidocq-runtime-parent} hands to Surefire as a system property.
 *
 * <p>Surefire puts on the module path only the dependencies the application module requires; a runtime dependency
 * no module requires, a JDBC driver for one, stays on the class path of the tests, and the application never saw
 * it, while {@code vidocq:dev} and the packaged application have it on their module path (BUG-20261030-06).</p>
 *
 * <p>Bound to {@code process-test-classes} by {@code vidocq-runtime-parent}.</p>
 */
@Mojo(name = "test-path",
      defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
      requiresDependencyResolution = ResolutionScope.RUNTIME,
      threadSafe = true)
public class VidocqTestPathMojo extends AbstractMojo {

    /** The property, read by Surefire's configuration in the parent and by {@code vidocq-runtime-junit}. */
    static final String PROPERTY = "vidocq.test.runtimePath";

    private static final Set<String> RUNTIME_SCOPES = Set.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Override
    public void execute() {
        project.getProperties().setProperty(PROPERTY, runtimePath(project.getArtifacts()));
    }

    /** The jars of the compile and runtime dependencies, in the order Maven resolved them. */
    static String runtimePath(Collection<Artifact> artifacts) {
        List<String> jars = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            File file = artifact.getFile();
            if (RUNTIME_SCOPES.contains(artifact.getScope()) && file != null && file.getName().endsWith(".jar")) {
                jars.add(file.getPath());
            }
        }
        return String.join(File.pathSeparator, jars);
    }
}
