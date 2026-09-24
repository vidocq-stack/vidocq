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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Locates the {@code vidocq-runtime-devservices-extension} jar, and the jars of the modules it {@code
 * requires} that are not already on the child's module path, among this plugin's own resolved
 * dependencies — so {@code vidocq:dev} and {@code vidocq:run} can add them all when dev services are
 * enabled. That extension reads the state file {@link
 * io.vidocq.runtime.devservices.host.DevServicesSession} writes and reports it to the application — it
 * never runs in the Maven JVM.
 *
 * <p>The extension's {@code module-info.java} declares {@code requires transitive
 * io.vidocq.runtime.spi.devconsole} ({@code vidocq-runtime-devconsole-spi}): unlike {@code
 * vidocq-runtime-spi}, which every Vidocq application already requires transitively through {@code
 * vidocq-runtime-core} and therefore always has on its own module path, an application is not required
 * to depend on the dev console SPI itself. Adding only the extension jar would then fail module
 * resolution at boot ({@code FindException}) as soon as dev services are turned on.</p>
 *
 * <p>Jars are resolved from {@code ${plugin.artifactMap}} (a {@code groupId:artifactId} keyed map of
 * this plugin's own dependencies), not from the project's dependencies: the extension is declared with
 * {@code <scope>runtime</scope>} on {@code vidocq-runtime-maven-plugin}'s own {@code pom.xml}, and pulls
 * {@code vidocq-runtime-devconsole-spi} in transitively. {@link #resolve} skips any of these artifacts
 * already present among the application's own resolved dependencies (by {@code groupId:artifactId}), so
 * the application's own copy wins and no module appears twice on the path.</p>
 *
 * <p>Public: both {@link VidocqDevMojo} (this package) and {@code VidocqRunMojo}
 * ({@code io.vidocq.runtime.maven}) call {@link #resolve}. Resolving must happen <b>before</b> {@link
 * io.vidocq.runtime.devservices.host.DevServicesSession#open} starts any dev-service container — a
 * missing jar here must never leave a started session with nothing to close it.</p>
 */
public final class DevServicesExtensionJar {

    static final String GROUP_ID = "io.vidocq.runtime";
    static final String ARTIFACT_ID = "vidocq-runtime-devservices-extension";
    private static final String EXTENSION_KEY = GROUP_ID + ":" + ARTIFACT_ID;

    /**
     * {@code vidocq-runtime-spi} is deliberately not listed here: every Vidocq application already
     * requires it transitively through {@code vidocq-runtime-core}, so it is always on the child's
     * module path already.
     */
    private static final String DEVCONSOLE_SPI_KEY = GROUP_ID + ":vidocq-runtime-devconsole-spi";

    /** In the order they should be added to the module path. */
    private static final List<String> REQUIRED_KEYS = List.of(EXTENSION_KEY, DEVCONSOLE_SPI_KEY);

    private DevServicesExtensionJar() {}

    /**
     * @param pluginArtifacts this plugin's own resolved dependencies, as injected through
     *                        {@code ${plugin.artifactMap}}
     * @throws MojoExecutionException when the extension jar is not among {@code pluginArtifacts} — it is
     *                                missing from {@code vidocq-runtime-maven-plugin}'s own {@code pom.xml}
     */
    public static Path find(Map<String, Artifact> pluginArtifacts) throws MojoExecutionException {
        return find(pluginArtifacts, EXTENSION_KEY);
    }

    /**
     * The extension jar and the jars of the modules it {@code requires} that are not already on the
     * child's module path — currently just {@code vidocq-runtime-devconsole-spi}. An artifact whose
     * {@code groupId:artifactId} is already present in {@code projectArtifacts} is skipped: the
     * application's own copy of that module wins, and it never appears twice on the path.
     *
     * @param pluginArtifacts  this plugin's own resolved dependencies ({@code ${plugin.artifactMap}})
     * @param projectArtifacts the application's own resolved dependencies, already on the module path
     *                         (e.g. {@code project.getArtifacts()}); may be {@code null} or empty
     * @throws MojoExecutionException when one of the required jars is not among {@code pluginArtifacts}
     */
    public static List<Path> resolve(Map<String, Artifact> pluginArtifacts, Collection<Artifact> projectArtifacts)
            throws MojoExecutionException {
        Set<String> onPath = new HashSet<>();
        if (projectArtifacts != null) {
            for (Artifact artifact : projectArtifacts) {
                onPath.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
            }
        }
        List<Path> jars = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            if (!onPath.contains(key)) {
                jars.add(find(pluginArtifacts, key));
            }
        }
        return jars;
    }

    private static Path find(Map<String, Artifact> pluginArtifacts, String key) throws MojoExecutionException {
        Artifact artifact = pluginArtifacts == null ? null : pluginArtifacts.get(key);
        if (artifact == null || artifact.getFile() == null) {
            throw new MojoExecutionException("Cannot find " + key + " among this plugin's own dependencies."
                    + " It must be declared (directly or transitively) in vidocq-runtime-maven-plugin/pom.xml.");
        }
        return artifact.getFile().toPath();
    }
}
