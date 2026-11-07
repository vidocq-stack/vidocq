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
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gives the effective model of an application that declares the plugin with {@code <extensions>true</extensions>}
 * the build {@code vidocq-runtime-parent} carries, where the model lacks it (Vidocq/vidocq#380): the Java release,
 * the compiler argument that turns the Vauban provides hint off, the five executions of the plugin, the runtime path
 * Surefire hands to the tests, and the m2e switch that runs the annotation processors in Eclipse. What the model has,
 * from the parent or from the application's own pom, is kept as it is: an execution is told by its goal, whatever its
 * id and phase, and a release by the properties or the compiler configuration.
 *
 * <p>Runs after Maven has read the projects, so the pom has been interpolated and the plugins of the lifecycle
 * (compiler, surefire) are in the model with Maven's default versions when the pom manages none. A property set here
 * still reaches a plugin parameter ({@code ${maven.compiler.release}}), which Maven evaluates when the mojo runs, and
 * an execution added here is in the plan Maven computes next.</p>
 *
 * <p>Compiled for Java 17 with {@link CodegenPaths}, for the same reason: an IDE may run its Maven import on an older
 * runtime.</p>
 */
final class ApplicationBuild {

    static final String RELEASE = "maven.compiler.release";
    static final String JAVA_RELEASE = "25";
    static final String PROVIDES_HINT_ARGUMENT = "-Avauban.providesHint";
    static final String PROVIDES_HINT = PROVIDES_HINT_ARGUMENT + "=false";
    static final String TEST_RUNTIME_PATH = "vidocq.test.runtimePath";
    static final String M2E_APT = "m2e.apt.activation";
    private static final String PLUGIN = "io.vidocq.runtime:vidocq-runtime-maven-plugin";
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    /** {id, goal, phase} of the executions vidocq-runtime-parent binds; a null phase is the goal's default. */
    private static final String[][] EXECUTIONS = {
            {"vidocq-checkpom", "checkpom", "validate"},
            {"vidocq-test-path", "test-path", "process-test-classes"},
            {"generate", "generate", "process-classes"},
            {"vidocq-check-module-info", "check-module-info", "process-classes"},
            {"default", "package", null},
    };

    private ApplicationBuild() {}

    /** Completes {@code model} and returns what it added, in words; nothing when the model had it all. */
    static List<String> complete(Model model) {
        if (model.getBuild() == null) {
            return List.of();
        }
        model.getBuild().flushPluginMap();
        Plugin vidocq = model.getBuild().getPluginsAsMap().get(PLUGIN);
        if (vidocq == null || !vidocq.isExtensions()) {
            return List.of();
        }
        List<String> added = new ArrayList<>();
        executions(vidocq, added);
        Plugin compiler = model.getBuild().getPluginsAsMap().get(COMPILER);
        release(model.getProperties(), compiler, added);
        if (compiler != null
                && configure(compiler, ApplicationBuild::lacksProvidesHint, ApplicationBuild::addProvidesHint)) {
            added.add("compiler argument " + PROVIDES_HINT);
        }
        Plugin surefire = model.getBuild().getPluginsAsMap().get(SUREFIRE);
        if (surefire != null
                && configure(surefire, ApplicationBuild::lacksTestRuntimePath, ApplicationBuild::addTestRuntimePath)) {
            added.add("surefire system property " + TEST_RUNTIME_PATH);
        }
        if (!model.getProperties().containsKey(M2E_APT)) {
            model.getProperties().setProperty(M2E_APT, "jdt_apt");
            added.add("property " + M2E_APT + "=jdt_apt");
        }
        return List.copyOf(added);
    }

    /** Binds every goal of the parent's executions the plugin does not bind yet, under the parent's id and phase. */
    private static void executions(Plugin vidocq, List<String> added) {
        Set<String> bound = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (PluginExecution execution : vidocq.getExecutions()) {
            bound.addAll(execution.getGoals());
            ids.add(execution.getId());
        }
        for (String[] spec : EXECUTIONS) {
            if (bound.contains(spec[1])) {
                continue;
            }
            PluginExecution execution = new PluginExecution();
            execution.setId(ids.contains(spec[0]) ? "vidocq-" + spec[1] + "-by-plugin" : spec[0]);
            execution.addGoal(spec[1]);
            if (spec[2] != null) {
                execution.setPhase(spec[2]);
            }
            // Maven copies the plugin's configuration into each execution when it builds the model: so does this.
            if (vidocq.getConfiguration() != null) {
                execution.setConfiguration(new Xpp3Dom((Xpp3Dom) vidocq.getConfiguration()));
            }
            vidocq.addExecution(execution);
            added.add("execution " + spec[1]);
        }
        vidocq.flushExecutionMap();
    }

    /**
     * Java {@value #JAVA_RELEASE}, unless the pom says which Java it compiles for, by a property or the compiler's
     * configuration ({@code compiler} is null in a model without the compiler plugin).
     */
    private static void release(Properties properties, Plugin compiler, List<String> added) {
        for (String property : new String[] {RELEASE, "maven.compiler.source", "maven.compiler.target"}) {
            if (properties.containsKey(property)) {
                return;
            }
        }
        Xpp3Dom configuration = compiler == null ? null : (Xpp3Dom) compiler.getConfiguration();
        if (configuration != null) {
            for (String element : new String[] {"release", "source", "target"}) {
                if (configuration.getChild(element) != null) {
                    return;
                }
            }
        }
        properties.setProperty(RELEASE, JAVA_RELEASE);
        added.add("property " + RELEASE + "=" + JAVA_RELEASE);
    }

    /**
     * Applies {@code add} to the plugin's configuration and to each execution's where {@code lacks} says so, creating
     * a configuration only where something goes in; whether anything was added.
     */
    private static boolean configure(Plugin plugin, Predicate<Xpp3Dom> lacks, java.util.function.Consumer<Xpp3Dom> add) {
        boolean changed = false;
        Xpp3Dom configuration = (Xpp3Dom) plugin.getConfiguration();
        if (lacks.test(configuration)) {
            if (configuration == null) {
                configuration = new Xpp3Dom("configuration");
                plugin.setConfiguration(configuration);
            }
            add.accept(configuration);
            changed = true;
        }
        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom executionConfiguration = (Xpp3Dom) execution.getConfiguration();
            if (lacks.test(executionConfiguration)) {
                if (executionConfiguration == null) {
                    executionConfiguration = new Xpp3Dom("configuration");
                    execution.setConfiguration(executionConfiguration);
                }
                add.accept(executionConfiguration);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean lacksProvidesHint(Xpp3Dom configuration) {
        Xpp3Dom args = configuration == null ? null : configuration.getChild("compilerArgs");
        if (args == null) {
            return true;
        }
        for (Xpp3Dom arg : args.getChildren("arg")) {
            if (arg.getValue() != null && arg.getValue().strip().startsWith(PROVIDES_HINT_ARGUMENT)) {
                return false;
            }
        }
        return true;
    }

    private static void addProvidesHint(Xpp3Dom configuration) {
        Xpp3Dom args = configuration.getChild("compilerArgs");
        if (args == null) {
            args = new Xpp3Dom("compilerArgs");
            configuration.addChild(args);
        }
        args.addChild(element("arg", PROVIDES_HINT));
    }

    private static boolean lacksTestRuntimePath(Xpp3Dom configuration) {
        Xpp3Dom variables = configuration == null ? null : configuration.getChild("systemPropertyVariables");
        return variables == null || variables.getChild(TEST_RUNTIME_PATH) == null;
    }

    /** The expression stays unresolved until {@code vidocq:test-path} sets the property, as the parent writes it. */
    private static void addTestRuntimePath(Xpp3Dom configuration) {
        Xpp3Dom variables = configuration.getChild("systemPropertyVariables");
        if (variables == null) {
            variables = new Xpp3Dom("systemPropertyVariables");
            configuration.addChild(variables);
        }
        variables.addChild(element(TEST_RUNTIME_PATH, "${" + TEST_RUNTIME_PATH + "}"));
    }

    private static Xpp3Dom element(String name, String value) {
        Xpp3Dom element = new Xpp3Dom(name);
        element.setValue(value);
        return element;
    }
}
