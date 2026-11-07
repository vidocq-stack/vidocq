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

import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Gives the effective model of an application that declares the plugin with {@code <extensions>true</extensions>}
 * the build {@code vidocq-runtime-parent} carries, where the model lacks it (Vidocq/vidocq#380): the Java release,
 * the source encoding, the compiler argument that turns the Vauban provides hint off, the five executions of the
 * plugin and the build identity ({@code build-info}, where {@code git-commit-id-maven-plugin} does not write it),
 * the versions of the lifecycle plugins, the runtime path Surefire hands to the tests, and the m2e switch that runs
 * the annotation processors in Eclipse. What the model has, from the parent or from the application's own pom, is
 * kept as it is: an execution is told by its goal, whatever its id and phase, and a release by the properties or the
 * compiler configuration.
 *
 * <p>Runs after Maven has read the projects, so the pom has been interpolated and the plugins of the lifecycle
 * (compiler, surefire) are in the model with Maven's default versions when the pom manages none. A property set here
 * still reaches a plugin parameter ({@code ${maven.compiler.release}}), which Maven evaluates when the mojo runs, and
 * an execution added here is in the plan Maven computes next.</p>
 *
 * <p>The values are the parent's, kept equal by {@code ApplicationBuildParentAlignmentTest}, which reads the parent's
 * pom in this reactor. Compiled for Java 17 with {@link CodegenPaths}, for the same reason: an IDE may run its Maven
 * import on an older runtime.</p>
 */
final class ApplicationBuild {

    static final String RELEASE = "maven.compiler.release";
    static final String JAVA_RELEASE = "25";
    static final String SOURCE_ENCODING = "project.build.sourceEncoding";
    static final String UTF_8 = "UTF-8";
    static final String PROVIDES_HINT_ARGUMENT = "-Avauban.providesHint";
    static final String PROVIDES_HINT = PROVIDES_HINT_ARGUMENT + "=false";
    static final String TEST_RUNTIME_PATH = "vidocq.test.runtimePath";
    static final String M2E_APT = "m2e.apt.activation";
    static final String M2E_APT_MODE = "jdt_apt";
    /**
     * The key under which the participant leaves what it added to a project ({@code MavenProject.getContextValue}),
     * the list {@link #complete} returns: {@code vidocq:checkpom} reads it to ask the pom for what an IDE reads there.
     */
    static final String CONTEXT_KEY = "io.vidocq.runtime.maven.ApplicationBuild.added";
    /** The goal that writes the build identity without the parent (the parent runs {@link #GIT_COMMIT_ID}). */
    static final String BUILD_INFO_GOAL = "build-info";
    /** The plugin vidocq-runtime-parent writes the build identity with: a project that runs it needs no build-info. */
    static final String GIT_COMMIT_ID = "io.github.git-commit-id:git-commit-id-maven-plugin";
    private static final String PLUGIN = "io.vidocq.runtime:vidocq-runtime-maven-plugin";
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String COMPILER_ARGS = "compilerArgs";
    private static final String SYSTEM_PROPERTY_VARIABLES = "systemPropertyVariables";
    /**
     * The executions vidocq-runtime-parent binds, then the build identity, which the parent writes with
     * git-commit-id-maven-plugin and the plugin with its own goal; a null phase is the goal's default.
     */
    private static final List<Execution> EXECUTIONS = List.of(
            new Execution("vidocq-checkpom", "checkpom", "validate"),
            new Execution("vidocq-test-path", "test-path", "process-test-classes"),
            new Execution("generate", "generate", "process-classes"),
            new Execution("vidocq-check-module-info", "check-module-info", "process-classes"),
            new Execution("default", "package", null),
            new Execution("vidocq-build-info", BUILD_INFO_GOAL, "initialize"));
    /**
     * artifactId to version of the lifecycle plugins vidocq-runtime-parent manages. Maven binds them with its own
     * defaults when no pom manages them: 3.13.0 for the compiler under Maven 3.9.9 and 4.0.0-rc-5, which fails on
     * Java 25 class files on the module path ("Unsupported class file major version 69").
     */
    private static final Map<String, String> LIFECYCLE_VERSIONS = lifecycleVersionsOfTheParent();
    /** The end of the model id Maven gives the source of what its default lifecycle bindings put in a model. */
    private static final String DEFAULT_BINDINGS = ":default-lifecycle-bindings";

    /** An execution of the plugin as the parent binds it: {@code phase} is null when the goal's default applies. */
    record Execution(String id, String goal, String phase) {}

    private ApplicationBuild() {}

    /** The executions the parent binds, in the parent's order. */
    static List<Execution> executions() {
        return EXECUTIONS;
    }

    /** The lifecycle plugins the parent manages, artifactId to version, in the parent's order. */
    static Map<String, String> lifecycleVersions() {
        return LIFECYCLE_VERSIONS;
    }

    /** Whether {@code added}, what {@link #complete} returned for a project, holds the Java release. */
    static boolean addedTheRelease(Object added) {
        return added instanceof List<?> list && list.contains(propertyNote(RELEASE, JAVA_RELEASE));
    }

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
        executions(vidocq, model.getBuild().getPluginsAsMap().containsKey(GIT_COMMIT_ID), added);
        versions(model, added);
        Plugin compiler = model.getBuild().getPluginsAsMap().get(COMPILER);
        release(model.getProperties(), compiler, added);
        if (!model.getProperties().containsKey(SOURCE_ENCODING)) {
            model.getProperties().setProperty(SOURCE_ENCODING, UTF_8);
            added.add(propertyNote(SOURCE_ENCODING, UTF_8));
        }
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
            model.getProperties().setProperty(M2E_APT, M2E_APT_MODE);
            added.add(propertyNote(M2E_APT, M2E_APT_MODE));
        }
        return List.copyOf(added);
    }

    private static Map<String, String> lifecycleVersionsOfTheParent() {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("maven-compiler-plugin", "3.15.0");
        versions.put("maven-surefire-plugin", "3.5.5");
        versions.put("maven-jar-plugin", "3.4.2");
        versions.put("maven-resources-plugin", "3.3.1");
        versions.put("maven-clean-plugin", "3.4.0");
        versions.put("maven-install-plugin", "3.1.3");
        versions.put("maven-deploy-plugin", "3.1.4");
        return Collections.unmodifiableMap(versions);
    }

    private static String propertyNote(String name, String value) {
        return "property " + name + "=" + value;
    }

    /**
     * Binds every goal of the parent's executions the plugin does not bind yet, under the parent's id and phase. The
     * build identity is written once: not by {@code build-info} where {@code git-commit-id-maven-plugin} runs
     * ({@code gitCommitId}), which the parent, or the pom, binds.
     */
    private static void executions(Plugin vidocq, boolean gitCommitId, List<String> added) {
        Set<String> bound = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (PluginExecution execution : vidocq.getExecutions()) {
            bound.addAll(execution.getGoals());
            ids.add(execution.getId());
        }
        if (gitCommitId) {
            bound.add(BUILD_INFO_GOAL);
        }
        for (Execution spec : EXECUTIONS) {
            if (bound.contains(spec.goal())) {
                continue;
            }
            PluginExecution execution = new PluginExecution();
            execution.setId(ids.contains(spec.id()) ? "vidocq-" + spec.goal() + "-by-plugin" : spec.id());
            execution.addGoal(spec.goal());
            if (spec.phase() != null) {
                execution.setPhase(spec.phase());
            }
            // Maven copies the plugin's configuration into each execution when it builds the model: so does this.
            if (vidocq.getConfiguration() != null) {
                execution.setConfiguration(new Xpp3Dom((Xpp3Dom) vidocq.getConfiguration()));
            }
            vidocq.addExecution(execution);
            added.add("execution " + spec.goal());
        }
        vidocq.flushExecutionMap();
    }

    /**
     * The parent's version for each lifecycle plugin whose version no pom set: Maven records the default bindings as
     * the source of the version it binds itself, and a pom as the source of one it read there.
     */
    private static void versions(Model model, List<String> added) {
        for (Map.Entry<String, String> spec : LIFECYCLE_VERSIONS.entrySet()) {
            Plugin plugin = model.getBuild().getPluginsAsMap().get("org.apache.maven.plugins:" + spec.getKey());
            if (plugin == null || spec.getValue().equals(plugin.getVersion())
                    || !fromDefaultBindings(plugin.getLocation("version"))) {
                continue;
            }
            plugin.setVersion(spec.getValue());
            added.add("version " + spec.getKey() + " " + spec.getValue());
        }
    }

    private static boolean fromDefaultBindings(InputLocation location) {
        if (location == null) {
            return true;
        }
        InputSource source = location.getSource();
        return source != null && source.getModelId() != null && source.getModelId().endsWith(DEFAULT_BINDINGS);
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
        added.add(propertyNote(RELEASE, JAVA_RELEASE));
    }

    /**
     * Applies {@code add} to the plugin's configuration and to each execution's where {@code lacks} says so, creating
     * a configuration only where something goes in; whether anything was added.
     */
    private static boolean configure(Plugin plugin, Predicate<Xpp3Dom> lacks, Consumer<Xpp3Dom> add) {
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
        Xpp3Dom args = configuration == null ? null : configuration.getChild(COMPILER_ARGS);
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
        child(configuration, COMPILER_ARGS).addChild(element("arg", PROVIDES_HINT));
    }

    private static boolean lacksTestRuntimePath(Xpp3Dom configuration) {
        Xpp3Dom variables = configuration == null ? null : configuration.getChild(SYSTEM_PROPERTY_VARIABLES);
        return variables == null || variables.getChild(TEST_RUNTIME_PATH) == null;
    }

    /** The expression stays unresolved until {@code vidocq:test-path} sets the property, as the parent writes it. */
    private static void addTestRuntimePath(Xpp3Dom configuration) {
        child(configuration, SYSTEM_PROPERTY_VARIABLES)
                .addChild(element(TEST_RUNTIME_PATH, "${" + TEST_RUNTIME_PATH + "}"));
    }

    /** The child {@code name} of {@code parent}, created when absent. */
    private static Xpp3Dom child(Xpp3Dom parent, String name) {
        Xpp3Dom child = parent.getChild(name);
        if (child == null) {
            child = new Xpp3Dom(name);
            parent.addChild(child);
        }
        return child;
    }

    private static Xpp3Dom element(String name, String value) {
        Xpp3Dom element = new Xpp3Dom(name);
        element.setValue(value);
        return element;
    }
}
