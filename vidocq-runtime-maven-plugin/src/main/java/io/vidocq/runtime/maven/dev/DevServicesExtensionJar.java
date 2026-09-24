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
import org.apache.maven.plugin.MojoExecutionException;

import java.nio.file.Path;
import java.util.Map;

/**
 * Locates the {@code vidocq-runtime-devservices-extension} jar among this plugin's own resolved
 * dependencies, so {@code vidocq:dev} and {@code vidocq:run} can add it to the child's module path
 * when dev services are enabled. That extension reads the state file {@link
 * io.vidocq.runtime.devservices.host.DevServicesSession} writes and reports it to the application —
 * it never runs in the Maven JVM.
 *
 * <p>The jar is resolved from {@code ${plugin.artifactMap}} (a {@code groupId:artifactId} keyed map of
 * this plugin's own dependencies), not from the project's dependencies: the extension is declared with
 * {@code <scope>runtime</scope>} on {@code vidocq-runtime-maven-plugin}'s own {@code pom.xml}.</p>
 *
 * <p>Public: both {@link VidocqDevMojo} (this package) and {@code VidocqRunMojo}
 * ({@code io.vidocq.runtime.maven}) call {@link #find}.</p>
 */
public final class DevServicesExtensionJar {

    static final String GROUP_ID = "io.vidocq.runtime";
    static final String ARTIFACT_ID = "vidocq-runtime-devservices-extension";
    private static final String KEY = GROUP_ID + ":" + ARTIFACT_ID;

    private DevServicesExtensionJar() {}

    /**
     * @param pluginArtifacts this plugin's own resolved dependencies, as injected through
     *                        {@code ${plugin.artifactMap}}
     * @throws MojoExecutionException when the extension jar is not among {@code pluginArtifacts} — it is
     *                                missing from {@code vidocq-runtime-maven-plugin}'s own {@code pom.xml}
     */
    public static Path find(Map<String, Artifact> pluginArtifacts) throws MojoExecutionException {
        Artifact artifact = pluginArtifacts == null ? null : pluginArtifacts.get(KEY);
        if (artifact == null || artifact.getFile() == null) {
            throw new MojoExecutionException("Cannot find the dev services extension jar (" + KEY
                    + ") among this plugin's own dependencies. It must be declared with <scope>runtime</scope>"
                    + " in vidocq-runtime-maven-plugin/pom.xml.");
        }
        return artifact.getFile().toPath();
    }
}
