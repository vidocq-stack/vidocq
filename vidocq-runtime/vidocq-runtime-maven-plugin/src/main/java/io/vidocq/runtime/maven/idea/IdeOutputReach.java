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
package io.vidocq.runtime.maven.idea;

import io.vidocq.runtime.maven.IdeOutput;
import org.apache.maven.model.Activation;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.List;

/**
 * The modules of a reactor an IDE still compiles into Maven's folders.
 *
 * <p>An IDE that reads a project through m2e gets a build directory of its own from one of two places
 * ({@link IdeOutput}): the lifecycle participant of this plugin, which Maven loads for a module that declares the
 * plugin as a build extension, or a profile activated by the property m2e sets, in the module's pom or in one it
 * inherits. A module with neither, the library next to an application for instance, is compiled by the IDE into
 * {@code target/classes}, which the build then packages: {@code vidocq:vscode} and {@code vidocq:eclipse} name it.</p>
 *
 * <p>Read from the projects Maven built for the goal, as {@link IdeaApplications} reads the applications: no pom is
 * parsed again. A module that declares the plugin and sets {@link IdeOutput#SKIP} is not named: its pom chose
 * {@code target/}.</p>
 */
final class IdeOutputReach {

    private static final String PACKAGING_POM = "pom";

    private IdeOutputReach() {
    }

    /**
     * The artifactIds, in the order of {@code projects}, of those an IDE compiles into Maven's folders: packaging
     * other than {@code pom}, the plugin not declared as a build extension, and no profile activated by
     * {@link IdeOutput#M2E_VERSION} in the project's own pom or in a pom it inherits.
     */
    static List<String> unreached(List<MavenProject> projects) {
        List<String> names = new ArrayList<>();
        for (MavenProject project : projects) {
            if (!PACKAGING_POM.equals(project.getPackaging()) && !byThePlugin(project) && !byAProfile(project)) {
                names.add(project.getArtifactId());
            }
        }
        return names;
    }

    private static boolean byThePlugin(MavenProject project) {
        Plugin plugin = project.getPlugin(IdeaApplications.PLUGIN_KEY);
        return plugin != null && plugin.isExtensions();
    }

    /** Whether the pom of {@code project}, or one above it, holds a profile the property of m2e activates. */
    private static boolean byAProfile(MavenProject project) {
        for (MavenProject current = project; current != null; current = current.getParent()) {
            for (Profile profile : current.getModel().getProfiles()) {
                Activation activation = profile.getActivation();
                if (activation != null && activation.getProperty() != null
                        && IdeOutput.M2E_VERSION.equals(activation.getProperty().getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
