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
 * The modules of a build an IDE still compiles into Maven's folders, and what would move each.
 *
 * <p>An IDE that reads a project through m2e gets a build directory of its own from one of two places
 * ({@link IdeOutput}): the lifecycle participant of this plugin, for an application of one module, or a profile
 * activated by the property m2e sets, in the module's pom or in one it inherits. A module with neither is compiled
 * by the IDE into {@code target/classes}, which the build then packages: {@code vidocq:vscode} and
 * {@code vidocq:eclipse} name it, with what its pom lacks.</p>
 *
 * <p>What the participant moves is asked of the participant's own rule ({@link IdeOutput#moves}), on the projects
 * Maven built for the goal: no pom is parsed again, and the rule is written once. The participant runs when Maven
 * loads it, which it does for a build one of whose modules declares the plugin as a build extension. A module that
 * sets {@link IdeOutput#SKIP} is not named: its pom chose {@code target/}.</p>
 */
final class IdeOutputReach {

    private static final String PACKAGING_POM = "pom";

    private IdeOutputReach() {
    }

    /**
     * The artifactIds, in the order of the build, of the modules an IDE compiles into Maven's folders.
     *
     * @param needingTheProfile   those the profile {@link IdeOutput#PROFILE} would move, in a pom they inherit: the
     *                            modules of a build of several modules, a library that does not declare the plugin
     * @param needingTheExtension those the participant would move if Maven loaded it: they declare the plugin
     *                            without {@code <extensions>true</extensions>}, and no module of the build has it
     */
    record Unreached(List<String> needingTheProfile, List<String> needingTheExtension) {
    }

    /**
     * What an IDE that reads {@code projects} compiles into Maven's folders: a module of packaging other than
     * {@code pom} that the participant does not move, that no profile activated by {@link IdeOutput#M2E_VERSION}
     * gives a build directory, in its own pom or in a pom it inherits, and that does not take the way out.
     */
    static Unreached unreached(List<MavenProject> projects) {
        boolean loaded = projects.stream().anyMatch(IdeOutputReach::asAnExtension);
        List<String> needingTheProfile = new ArrayList<>();
        List<String> needingTheExtension = new ArrayList<>();
        for (MavenProject project : projects) {
            if (PACKAGING_POM.equals(project.getPackaging()) || IdeOutput.takesTheWayOut(project)
                    || byAProfile(project)) {
                continue;
            }
            if (!IdeOutput.moves(project, projects)) {
                needingTheProfile.add(project.getArtifactId());
            } else if (!loaded) {
                needingTheExtension.add(project.getArtifactId());
            }
        }
        return new Unreached(List.copyOf(needingTheProfile), List.copyOf(needingTheExtension));
    }

    /** Whether {@code project} declares the plugin as a build extension, which is when Maven loads the participant. */
    private static boolean asAnExtension(MavenProject project) {
        Plugin plugin = project.getPlugin(IdeaApplications.PLUGIN_KEY);
        return plugin != null && plugin.isExtensions();
    }

    /**
     * Whether the pom of {@code project}, or one above it, holds a profile that the property of m2e activates and
     * that sets the build directory. A profile on that property that holds something else, the lifecycle mapping of
     * m2e for instance, moves nothing.
     */
    private static boolean byAProfile(MavenProject project) {
        for (MavenProject current = project; current != null; current = current.getParent()) {
            for (Profile profile : current.getModel().getProfiles()) {
                Activation activation = profile.getActivation();
                if (activation != null && activation.getProperty() != null
                        && IdeOutput.M2E_VERSION.equals(activation.getProperty().getName())
                        && profile.getBuild() != null && profile.getBuild().getDirectory() != null) {
                    return true;
                }
            }
        }
        return false;
    }
}
