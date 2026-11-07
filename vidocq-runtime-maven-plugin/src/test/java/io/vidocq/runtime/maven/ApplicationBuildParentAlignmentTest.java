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

import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Vidocq/vidocq#380: what {@link ApplicationBuild} gives an application without {@code vidocq-runtime-parent} is what
 * the parent gives one with it. The parent's pom, in this reactor, is the single source of truth: this test fails
 * when the two diverge, so that a change of the parent reaches the plugin, and the reverse.
 */
class ApplicationBuildParentAlignmentTest {

    private static final Path PARENT_POM = Path.of("..", "vidocq-runtime-parent", "pom.xml");
    private static final String MAVEN_PLUGINS = "org.apache.maven.plugins";

    @Test
    void theLifecyclePluginVersionsAreTheParents() throws Exception {
        Map<String, String> managed = new TreeMap<>();
        for (Plugin plugin : parent().getBuild().getPluginManagement().getPlugins()) {
            String version = plugin.getVersion();
            if (MAVEN_PLUGINS.equals(plugin.getGroupId()) && version != null && !version.startsWith("${")) {
                managed.put(plugin.getArtifactId(), version);
            }
        }

        assertEquals(managed, new TreeMap<>(ApplicationBuild.lifecycleVersions()));
    }

    @Test
    void theExecutionsOfThePluginAreTheParents() throws Exception {
        List<String> parents = new ArrayList<>();
        for (PluginExecution execution : managed(parent(), "io.vidocq.runtime", "vidocq-runtime-maven-plugin")
                .getExecutions()) {
            for (String goal : execution.getGoals()) {
                parents.add(execution.getId() + ":" + goal + "@" + (execution.getPhase() == null ? "" : execution.getPhase()));
            }
        }

        List<String> plugins = new ArrayList<>();
        for (ApplicationBuild.Execution execution : ApplicationBuild.executions()) {
            // The parent writes the build identity with git-commit-id-maven-plugin, the plugin with its own goal.
            if (!ApplicationBuild.BUILD_INFO_GOAL.equals(execution.goal())) {
                plugins.add(execution.id() + ":" + execution.goal() + "@" + (execution.phase() == null ? "" : execution.phase()));
            }
        }
        assertEquals(parents, plugins);
    }

    /** The parent binds {@code git-commit-id-maven-plugin}'s {@code revision}: what {@code vidocq:build-info} replaces. */
    @Test
    void theParentWritesTheBuildIdentityWithGitCommitId() throws Exception {
        Plugin gitCommitId = parent().getBuild().getPluginsAsMap().get(ApplicationBuild.GIT_COMMIT_ID);

        assertEquals(List.of("revision"), gitCommitId.getExecutions().getFirst().getGoals());
        assertEquals("initialize", gitCommitId.getExecutions().getFirst().getPhase());
    }

    @Test
    void theReleaseAndTheM2eSwitchAreTheParents() throws Exception {
        Model parent = parent();

        // The parent is the reference; the constants are what the plugin writes.
        assertEquals(ApplicationBuild.JAVA_RELEASE, parent.getProperties().getProperty(ApplicationBuild.RELEASE));
        assertEquals(ApplicationBuild.M2E_APT_MODE, parent.getProperties().getProperty(ApplicationBuild.M2E_APT));
    }

    @Test
    void theCompilerArgumentIsTheParents() throws Exception {
        Xpp3Dom configuration = (Xpp3Dom) managed(parent(), MAVEN_PLUGINS, "maven-compiler-plugin").getConfiguration();

        List<String> arguments = new ArrayList<>();
        for (Xpp3Dom arg : configuration.getChild("compilerArgs").getChildren("arg")) {
            arguments.add(arg.getValue().strip());
        }
        assertEquals(List.of(ApplicationBuild.PROVIDES_HINT), arguments);
    }

    @Test
    void theRuntimePathOfTheTestsIsTheParents() throws Exception {
        Xpp3Dom configuration = (Xpp3Dom) managed(parent(), MAVEN_PLUGINS, "maven-surefire-plugin").getConfiguration();

        Xpp3Dom variables = configuration.getChild("systemPropertyVariables");
        assertEquals(1, variables.getChildCount(), "the parent hands the tests one system property");
        assertEquals("${" + ApplicationBuild.TEST_RUNTIME_PATH + "}",
                variables.getChild(ApplicationBuild.TEST_RUNTIME_PATH).getValue().strip());
    }

    private static Plugin managed(Model parent, String groupId, String artifactId) {
        Plugin plugin = parent.getBuild().getPluginManagement().getPluginsAsMap().get(groupId + ":" + artifactId);
        if (plugin == null) {
            throw new AssertionError("vidocq-runtime-parent does not manage " + artifactId);
        }
        return plugin;
    }

    private static Model parent() throws Exception {
        try (Reader reader = Files.newBufferedReader(PARENT_POM)) {
            return new MavenXpp3Reader().read(reader);
        }
    }
}
