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

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Vidocq/vidocq#380: an application without {@code vidocq-runtime-parent} declares the plugin with
 * {@code <extensions>true</extensions>} and nothing else. The participant gives its model the build the parent
 * carries where the model lacks it, and leaves a model that has it, the parent's or the application's own, as it is.
 */
class ApplicationBuildTest {

    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final List<String> PARENT_EXECUTIONS = List.of(
            "vidocq-checkpom:checkpom@validate",
            "vidocq-test-path:test-path@process-test-classes",
            "generate:generate@process-classes",
            "vidocq-check-module-info:check-module-info@process-classes",
            "default:package@");

    @Test
    void aProjectWithoutTheParentGetsTheFiveExecutionsOfThePlugin() {
        Model model = application();

        ApplicationBuild.complete(model);

        assertEquals(PARENT_EXECUTIONS, executions(vidocq(model)));
    }

    /** An execution the project binds itself, whatever its id and phase, is kept and its goal is not bound twice. */
    @Test
    void anExecutionTheProjectDeclaresIsKeptAsItIs() {
        Model model = application();
        vidocq(model).addExecution(execution("my-package", "package", "install"));

        ApplicationBuild.complete(model);

        assertEquals(List.of("my-package:package@install",
                "vidocq-checkpom:checkpom@validate",
                "vidocq-test-path:test-path@process-test-classes",
                "generate:generate@process-classes",
                "vidocq-check-module-info:check-module-info@process-classes"), executions(vidocq(model)));
    }

    @Test
    void theCompilerGetsTheProvidesHintArgumentInItsConfigurationAndEveryExecution() {
        Model model = application();

        ApplicationBuild.complete(model);

        Plugin compiler = plugin(model, COMPILER);
        assertEquals(List.of("-Avauban.providesHint=false"), compilerArgs(compiler.getConfiguration()));
        for (PluginExecution execution : compiler.getExecutions()) {
            assertEquals(List.of("-Avauban.providesHint=false"), compilerArgs(execution.getConfiguration()),
                    execution.getId());
        }
    }

    /** The value the project chooses for the hint wins, and an argument of its own stays in place. */
    @Test
    void anArgumentTheProjectDeclaresIsNotAddedAgain() {
        Model model = application();
        Xpp3Dom configuration = configuration(compilerArgs("-parameters", "-Avauban.providesHint=true"));
        plugin(model, COMPILER).setConfiguration(configuration);
        plugin(model, COMPILER).getExecutions().forEach(e -> e.setConfiguration(new Xpp3Dom(configuration)));

        ApplicationBuild.complete(model);

        assertEquals(List.of("-parameters", "-Avauban.providesHint=true"), compilerArgs(configuration));
        for (PluginExecution execution : plugin(model, COMPILER).getExecutions()) {
            assertEquals(List.of("-parameters", "-Avauban.providesHint=true"),
                    compilerArgs(execution.getConfiguration()), execution.getId());
        }
    }

    @Test
    void theJavaReleaseIs25WhenTheProjectSetsNone() {
        Model model = application();

        ApplicationBuild.complete(model);

        assertEquals("25", model.getProperties().getProperty("maven.compiler.release"));
    }

    @Test
    void aJavaReleaseTheProjectSetsIsKept() {
        Model byProperty = application();
        byProperty.getProperties().setProperty("maven.compiler.release", "21");
        Model byConfiguration = application();
        plugin(byConfiguration, COMPILER).setConfiguration(configuration(element("release", "21")));
        Model bySourceAndTarget = application();
        bySourceAndTarget.getProperties().setProperty("maven.compiler.source", "21");
        bySourceAndTarget.getProperties().setProperty("maven.compiler.target", "21");

        ApplicationBuild.complete(byProperty);
        ApplicationBuild.complete(byConfiguration);
        ApplicationBuild.complete(bySourceAndTarget);

        assertEquals("21", byProperty.getProperties().getProperty("maven.compiler.release"));
        assertNull(byConfiguration.getProperties().getProperty("maven.compiler.release"));
        assertNull(bySourceAndTarget.getProperties().getProperty("maven.compiler.release"));
    }

    /** The expression stays unresolved in the model: {@code vidocq:test-path} gives it its value before the tests. */
    @Test
    void surefireGetsTheRuntimePathOfTheTestsInItsConfigurationAndEveryExecution() {
        Model model = application();

        ApplicationBuild.complete(model);

        Plugin surefire = plugin(model, SUREFIRE);
        assertEquals("${vidocq.test.runtimePath}", runtimePath(surefire.getConfiguration()));
        for (PluginExecution execution : surefire.getExecutions()) {
            assertEquals("${vidocq.test.runtimePath}", runtimePath(execution.getConfiguration()), execution.getId());
        }
    }

    @Test
    void aRuntimePathTheProjectDeclaresIsKept() {
        Model model = application();
        Xpp3Dom variables = new Xpp3Dom("systemPropertyVariables");
        variables.addChild(element("vidocq.test.runtimePath", "${my.path}"));
        plugin(model, SUREFIRE).setConfiguration(configuration(variables));

        ApplicationBuild.complete(model);

        Xpp3Dom configuration = (Xpp3Dom) plugin(model, SUREFIRE).getConfiguration();
        assertEquals("${my.path}", runtimePath(configuration));
        assertEquals(1, configuration.getChild("systemPropertyVariables").getChildCount());
    }

    @Test
    void m2eRunsTheAnnotationProcessors() {
        Model model = application();

        ApplicationBuild.complete(model);

        assertEquals("jdt_apt", model.getProperties().getProperty("m2e.apt.activation"));
    }

    /** The model of an application under vidocq-runtime-parent already has all of it: nothing changes. */
    @Test
    void aProjectWithTheParentSeesNoChange() {
        Model model = application();
        List<String> firstPass = ApplicationBuild.complete(model);
        // Five executions, the release, the compiler argument, the Surefire property and the m2e switch.
        assertEquals(9, firstPass.size(), firstPass.toString());

        List<String> secondPass = ApplicationBuild.complete(model);

        assertEquals(List.of(), secondPass);
        assertEquals(PARENT_EXECUTIONS, executions(vidocq(model)));
        assertEquals(List.of("-Avauban.providesHint=false"), compilerArgs(plugin(model, COMPILER).getConfiguration()));
        assertEquals(1, ((Xpp3Dom) plugin(model, SUREFIRE).getConfiguration())
                .getChild("systemPropertyVariables").getChildCount());
    }

    @Test
    void aModuleWithoutThePluginIsLeftAlone() {
        Model model = application();
        model.getBuild().getPlugins().removeIf(p -> p.getArtifactId().equals("vidocq-runtime-maven-plugin"));

        assertEquals(List.of(), ApplicationBuild.complete(model));
        assertNull(plugin(model, COMPILER).getConfiguration());
        assertNull(model.getProperties().getProperty("maven.compiler.release"));
    }

    /** Without {@code <extensions>true</extensions>} the participant does not run: the pom says what it gets. */
    @Test
    void aModuleThatDeclaresThePluginWithoutExtensionsIsLeftAlone() {
        Model model = application();
        vidocq(model).setExtensions(false);

        assertEquals(List.of(), ApplicationBuild.complete(model));
        assertEquals(List.of(), executions(vidocq(model)));
    }

    // ---- fixtures: the effective model of an application without the parent, as Maven builds it ----

    /**
     * The plugin with {@code <extensions>true</extensions>} and no execution, and the compiler and surefire plugins
     * Maven binds to the {@code jar} lifecycle, each with its default executions and no configuration.
     */
    private static Model application() {
        Model model = new Model();
        model.setPackaging("jar");
        Build build = new Build();
        Plugin vidocq = new Plugin();
        vidocq.setGroupId("io.vidocq.runtime");
        vidocq.setArtifactId("vidocq-runtime-maven-plugin");
        vidocq.setExtensions(true);
        build.addPlugin(vidocq);
        build.addPlugin(lifecyclePlugin("maven-compiler-plugin", "default-compile", "default-testCompile"));
        build.addPlugin(lifecyclePlugin("maven-surefire-plugin", "default-test"));
        model.setBuild(build);
        return model;
    }

    private static Plugin lifecyclePlugin(String artifactId, String... executionIds) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId(artifactId);
        for (String id : executionIds) {
            PluginExecution execution = new PluginExecution();
            execution.setId(id);
            plugin.addExecution(execution);
        }
        return plugin;
    }

    private static PluginExecution execution(String id, String goal, String phase) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.addGoal(goal);
        execution.setPhase(phase);
        return execution;
    }

    private static Plugin vidocq(Model model) {
        return plugin(model, "io.vidocq.runtime:vidocq-runtime-maven-plugin");
    }

    private static Plugin plugin(Model model, String key) {
        model.getBuild().flushPluginMap();
        return model.getBuild().getPluginsAsMap().get(key);
    }

    /** {@code id:goal@phase} of every execution, an empty phase when the execution leaves it to the goal. */
    private static List<String> executions(Plugin plugin) {
        List<String> result = new ArrayList<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            for (String goal : execution.getGoals()) {
                result.add(execution.getId() + ":" + goal + "@" + (execution.getPhase() == null ? "" : execution.getPhase()));
            }
        }
        return result;
    }

    private static Xpp3Dom configuration(Xpp3Dom... children) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        for (Xpp3Dom child : children) {
            configuration.addChild(child);
        }
        return configuration;
    }

    private static Xpp3Dom compilerArgs(String... args) {
        Xpp3Dom compilerArgs = new Xpp3Dom("compilerArgs");
        for (String arg : args) {
            compilerArgs.addChild(element("arg", arg));
        }
        return compilerArgs;
    }

    private static Xpp3Dom element(String name, String value) {
        Xpp3Dom element = new Xpp3Dom(name);
        element.setValue(value);
        return element;
    }

    private static List<String> compilerArgs(Object configuration) {
        List<String> result = new ArrayList<>();
        Xpp3Dom args = configuration == null ? null : ((Xpp3Dom) configuration).getChild("compilerArgs");
        if (args != null) {
            for (Xpp3Dom arg : args.getChildren("arg")) {
                result.add(arg.getValue());
            }
        }
        return result;
    }

    private static String runtimePath(Object configuration) {
        Xpp3Dom variables = configuration == null ? null : ((Xpp3Dom) configuration).getChild("systemPropertyVariables");
        Xpp3Dom path = variables == null ? null : variables.getChild("vidocq.test.runtimePath");
        return path == null ? null : path.getValue();
    }
}
