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

import io.vidocq.runtime.maven.idea.IdeaApplications.Diagnostic;
import io.vidocq.runtime.maven.idea.IdeaApplications.Discovery;
import io.vidocq.runtime.maven.idea.IdeaApplications.Level;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards which modules of a reactor get an IntelliJ run configuration, and with which values.
 *
 * <p>A module is a Vidocq application when it is not an aggregator, declares
 * {@code vidocq-runtime-maven-plugin} in its build plugins (the before-launch step runs
 * {@code vidocq:generate} on its pom, which must resolve the plugin and its configuration there) and
 * declares an application main class. Everything is read from each module's own model: a command-line
 * {@code -D} would otherwise apply to every module of the aggregator at once, and the committed files
 * would depend on how Maven was invoked.
 */
class IdeaApplicationsTest {

    @TempDir
    Path root;

    @BeforeEach
    void rootPom() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
    }

    @Test
    void aJarModuleWithThePluginAndAMainClassIsAnApplication() throws IOException {
        MavenProject alpha = application("alpha", "com.example.alpha.AlphaApp");

        Discovery discovery = discover(alpha);

        assertEquals(List.of(new IdeaApplication("com.example:alpha", "com.example.alpha.AlphaApp", "AlphaApp",
                "alpha", "alpha/pom.xml", "vidocq:generate")), discovery.applications());
        assertEquals(List.of(), discovery.errors());
    }

    @Test
    void aNestedModuleGetsItsPathRelativeToTheProjectDirectory() throws IOException {
        MavenProject beta = application("apps/beta", "com.example.beta.BetaApp");

        assertEquals("apps/beta/pom.xml", discover(beta).applications().get(0).pomPath());
    }

    @Test
    void anAggregatorIsNotAnApplication() throws IOException {
        MavenProject parent = application("", "com.example.App");
        parent.getModel().setPackaging("pom");

        Discovery discovery = discover(parent);

        assertEquals(List.of(), discovery.applications());
        assertHas(discovery, Level.DEBUG, "Vidocq idea: com.example:app is not a Vidocq application: packaging pom");
    }

    @Test
    void thePluginOnlyInPluginManagementIsNotEnough() throws IOException {
        MavenProject library = project("library");
        library.getProperties().setProperty("vidocq.mainClass", "com.example.App");
        PluginManagement management = new PluginManagement();
        management.addPlugin(vidocqPlugin());
        library.getModel().getBuild().setPluginManagement(management);

        Discovery discovery = discover(library);

        assertEquals(List.of(), discovery.applications());
        assertHas(discovery, Level.DEBUG,
                "Vidocq idea: com.example:library is not a Vidocq application: vidocq-runtime-maven-plugin is not in its <build><plugins>");
    }

    @Test
    void aModuleWithoutApplicationMainClassIsNotAnApplication() throws IOException {
        MavenProject library = project("library");
        library.getModel().getBuild().addPlugin(vidocqPlugin());
        MavenProject runtimeMain = application("runtime-main", "io.vidocq.runtime.core.Vidocq");

        Discovery discovery = discover(library, runtimeMain);

        assertEquals(List.of(), discovery.applications());
        assertHas(discovery, Level.DEBUG,
                "Vidocq idea: com.example:library is not a Vidocq application: no application main class (vidocq.mainClass)");
    }

    @Test
    void thePluginLevelMainClassWinsAndALegacyModuleReferenceKeepsItsClass() throws IOException {
        MavenProject alpha = application("alpha", "com.example.alpha.PropertyApp");
        alpha.getPlugin(IdeaApplications.PLUGIN_KEY).setConfiguration(dom("configuration",
                leaf("mainClass", "com.example.alpha/com.example.alpha.ConfiguredApp")));

        IdeaApplication application = discover(alpha).applications().get(0);

        assertEquals("com.example.alpha.ConfiguredApp", application.mainClass());
        assertEquals("ConfiguredApp", application.configurationName());
    }

    @Test
    void anInvalidMainClassIsAConfigurationError() throws IOException {
        MavenProject unresolved = application("unresolved", "${vidocq.mainClass}");
        MavenProject spaced = application("spaced", "a b.C");

        Discovery discovery = discover(unresolved, spaced);

        assertEquals(List.of(), discovery.applications());
        assertEquals(List.of(
                "Vidocq idea: com.example:unresolved: the main class \"${vidocq.mainClass}\" is not a Java class name.",
                "Vidocq idea: com.example:spaced: the main class \"a b.C\" is not a Java class name."),
                discovery.errors());
    }

    @Test
    void aNestedMainClassIsAValidBinaryName() throws IOException {
        MavenProject app = application("app", "com.example.Outer$Main");

        IdeaApplication application = discover(app).applications().get(0);

        assertEquals("com.example.Outer$Main", application.mainClass());
        assertEquals("Main", application.configurationName());
    }

    @Test
    void aPomOutsideTheProjectDirectoryIsAConfigurationError(@TempDir Path elsewhere) throws IOException {
        MavenProject outside = application("alpha", "com.example.alpha.AlphaApp");
        Files.createDirectories(elsewhere.resolve("alpha"));
        Files.writeString(elsewhere.resolve("alpha/pom.xml"), "<project/>");
        outside.setFile(elsewhere.resolve("alpha/pom.xml").toFile());

        Discovery discovery = discover(outside);

        assertEquals(1, discovery.errors().size());
        assertTrue(discovery.errors().get(0).startsWith("Vidocq idea: com.example:alpha: its pom "), discovery.errors().get(0));
        assertTrue(discovery.errors().get(0).endsWith(" Run vidocq:idea from a directory that contains every application,"
                + " or set -Dvidocq.idea.projectDirectory."), discovery.errors().get(0));
    }

    @Test
    void aSymbolicLinkToTheProjectDirectoryStillContainsItsModules(@TempDir Path links) throws IOException {
        MavenProject alpha = application("alpha", "com.example.alpha.AlphaApp");
        Path link = Files.createSymbolicLink(links.resolve("root"), root);

        Discovery discovery = IdeaApplications.discover(List.of(alpha), link, new Properties());

        assertEquals(List.of(), discovery.errors());
        assertEquals("alpha/pom.xml", discovery.applications().get(0).pomPath());
    }

    @Test
    void configurationAndModuleNamesCanBeSetInThePom() throws IOException {
        MavenProject beta = application("beta", "com.example.beta.BetaApp");
        beta.getProperties().setProperty("vidocq.idea.configurationName", "Beta server");
        beta.getProperties().setProperty("vidocq.idea.moduleName", "beta.main");

        IdeaApplication application = discover(beta).applications().get(0);

        assertEquals("Beta server", application.configurationName());
        assertEquals("beta.main", application.moduleName());
    }

    @Test
    void blankOrControlCharacterNamesAreConfigurationErrors() throws IOException {
        MavenProject blank = application("blank", "com.example.App");
        blank.getProperties().setProperty("vidocq.idea.configurationName", " ");
        MavenProject control = application("control", "com.example.App");
        control.getProperties().setProperty("vidocq.idea.moduleName", "a\tb");

        Discovery discovery = discover(blank, control);

        assertEquals(List.of(
                "Vidocq idea: com.example:blank: vidocq.idea.configurationName must not be blank or contain control characters.",
                "Vidocq idea: com.example:control: vidocq.idea.moduleName must not be blank or contain control characters."),
                discovery.errors());
    }

    @Test
    void aModuleIsLeftOutByItsPom() throws IOException {
        MavenProject alpha = application("alpha", "com.example.alpha.AlphaApp");
        alpha.getProperties().setProperty("vidocq.idea.exclude", "true");

        Discovery discovery = discover(alpha);

        assertEquals(List.of(), discovery.applications());
        assertHas(discovery, Level.INFO, "Vidocq idea: com.example:alpha excluded (vidocq.idea.exclude=true in its pom)");
    }

    /**
     * {@code vidocq.idea.skip} is the goal's own switch. Maven evaluates a goal parameter against the command
     * line, then the properties of the top-level project, which is the first selected project when
     * {@code -pl} leaves the root out: a module setting with that name would skip the whole goal there. The
     * module setting is therefore {@code vidocq.idea.exclude}, and a module pom that sets
     * {@code vidocq.idea.skip} is reported instead of silently doing nothing.
     */
    @Test
    void theGoalSkipPropertyInAModulePomDoesNotLeaveItOutAndIsReported() throws IOException {
        MavenProject alpha = application("alpha", "com.example.alpha.AlphaApp");
        alpha.getProperties().setProperty("vidocq.idea.skip", "true");
        alpha.setOriginalModel(alpha.getModel().clone());
        MavenProject inheriting = application("inheriting", "com.example.App");
        inheriting.getProperties().setProperty("vidocq.idea.skip", "true");
        inheriting.setOriginalModel(new Model());

        Discovery discovery = discover(alpha, inheriting);

        assertEquals(List.of("com.example:alpha", "com.example:inheriting"),
                discovery.applications().stream().map(IdeaApplication::coordinates).toList());
        assertHas(discovery, Level.WARN, "Vidocq idea: com.example:alpha sets vidocq.idea.skip=true in its pom,"
                + " which does not leave a module out: that property skips the whole goal, from the command line"
                + " or from the top-level project of the build. Set <vidocq.idea.exclude>true</vidocq.idea.exclude>"
                + " to leave out this module and the modules that inherit its properties.");
        assertTrue(discovery.diagnostics().stream().noneMatch(d -> d.message().contains("com.example:inheriting sets")),
                "only the pom that declares the property is reported: " + discovery.diagnostics());
    }

    @Test
    void aMainClassSetOnlyOnExecutionsIsReported() throws IOException {
        MavenProject cassini = project("cassini");
        Plugin plugin = vidocqPlugin();
        plugin.addExecution(execution("jlink", "jlink", dom("configuration", leaf("mainClass", "com.example.App"))));
        plugin.addExecution(execution("jpackage", "jpackage", dom("configuration", leaf("mainClass", "com.example.App"))));
        cassini.getModel().getBuild().addPlugin(plugin);

        Discovery discovery = discover(cassini);

        assertEquals(List.of(), discovery.applications());
        assertHas(discovery, Level.WARN, "Vidocq idea: com.example:cassini sets mainClass only in the configuration"
                + " of execution(s) jlink, jpackage, which vidocq:idea does not read. Declare <vidocq.mainClass>"
                + " (and <vidocq.mainModule>) in its <properties> to get a run configuration.");
    }

    @Test
    void aMainModuleWithoutMainClassIsReported() throws IOException {
        MavenProject module = project("module-only");
        module.getModel().getBuild().addPlugin(vidocqPlugin());
        module.getProperties().setProperty("vidocq.mainModule", "com.example");

        Discovery discovery = discover(module);

        assertHas(discovery, Level.INFO, "Vidocq idea: com.example:module-only sets vidocq.mainModule but no"
                + " application main class; an IntelliJ Application configuration needs vidocq.mainClass.");
    }

    /**
     * {@code lc4jcdi-on-vidocq}: {@code <scanDependencies>} at plugin level and an execution that only
     * binds {@code generate}. Maven copies the plugin-level configuration into every execution of the
     * effective model, so the execution has no configuration of its own and the measured
     * {@code goal="vidocq:generate"} is kept.
     */
    @Test
    void theGenerateStepStaysOnTheDefaultExecutionWhenNoExecutionConfiguresIt() throws IOException {
        MavenProject app = application("app", "com.example.App");
        Plugin plugin = app.getPlugin(IdeaApplications.PLUGIN_KEY);
        plugin.setConfiguration(scanDependencies("dev.langchain4j.cdi.mcp:langchain4j-cdi-mcp-server"));
        plugin.addExecution(execution("generate", "generate", scanDependencies("dev.langchain4j.cdi.mcp:langchain4j-cdi-mcp-server")));
        plugin.addExecution(execution("package", "package", scanDependencies("dev.langchain4j.cdi.mcp:langchain4j-cdi-mcp-server")));

        assertEquals("vidocq:generate", discover(app).applications().get(0).generateGoal());
    }

    /**
     * A goal run on its own uses the {@code default-cli} execution, which does not see an execution's
     * configuration: with {@code <scanDependencies>} on the {@code generate} execution only, a plain
     * {@code vidocq:generate} indexes none of those dependencies. {@code vidocq:generate@generate} uses
     * that execution's configuration (Maven 3.3.1+).
     */
    @Test
    void theGenerateStepRunsTheOnlyExecutionThatConfiguresIt() throws IOException {
        MavenProject app = application("app", "com.example.App");
        Plugin plugin = app.getPlugin(IdeaApplications.PLUGIN_KEY);
        plugin.addExecution(execution("generate", "generate", scanDependencies("io.vidocq.examples:*")));
        plugin.addExecution(execution("default-cli", "generate", scanDependencies("ignored:*")));

        assertEquals("vidocq:generate@generate", discover(app).applications().get(0).generateGoal());
    }

    @Test
    void severalConfiguredGenerateExecutionsFallBackToTheDefaultOneWithAWarning() throws IOException {
        MavenProject app = application("app", "com.example.App");
        Plugin plugin = app.getPlugin(IdeaApplications.PLUGIN_KEY);
        plugin.addExecution(execution("generate-a", "generate", scanDependencies("a:*")));
        plugin.addExecution(execution("generate-b", "generate", scanDependencies("b:*")));

        Discovery discovery = discover(app);

        assertEquals("vidocq:generate", discovery.applications().get(0).generateGoal());
        assertHas(discovery, Level.WARN, "Vidocq idea: com.example:app configures the generate goal on several"
                + " executions (generate-a, generate-b). The before-launch step runs one Maven invocation;"
                + " it runs \"vidocq:generate\", which sees the plugin-level configuration only. Move the shared"
                + " settings, such as <scanDependencies>, to the plugin-level <configuration>.");
    }

    @Test
    void commandLineApplicationSettingsAreIgnoredAndReported() throws IOException {
        MavenProject alpha = application("alpha", "com.example.alpha.AlphaApp");
        Properties userProperties = new Properties();
        userProperties.setProperty("vidocq.mainClass", "com.example.Other");
        userProperties.setProperty("vidocq.idea.moduleName", "other");
        userProperties.setProperty("vidocq.idea.exclude", "true");

        Discovery discovery = IdeaApplications.discover(List.of(alpha), root, userProperties);

        assertEquals("com.example.alpha.AlphaApp", discovery.applications().get(0).mainClass());
        assertEquals("alpha", discovery.applications().get(0).moduleName());
        assertHas(discovery, Level.WARN, "Vidocq idea: ignoring -Dvidocq.idea.exclude from the command line:"
                + " application settings are read from each module's pom, so that the files in .run/ do not"
                + " depend on how Maven was invoked.");
        assertHas(discovery, Level.WARN, "Vidocq idea: ignoring -Dvidocq.mainClass from the command line:"
                + " application settings are read from each module's pom, so that the files in .run/ do not"
                + " depend on how Maven was invoked.");
        assertHas(discovery, Level.WARN, "Vidocq idea: ignoring -Dvidocq.idea.moduleName from the command line:"
                + " application settings are read from each module's pom, so that the files in .run/ do not"
                + " depend on how Maven was invoked.");
    }

    /**
     * IntelliJ splits a Maven module into {@code <name>.main} and {@code <name>.test} in some cases
     * ({@code MavenProjectImportContextProvider.needCreateCompoundModule}). The artifactId module then
     * still exists, so the gutter silently creates a temporary configuration, without the Maven step,
     * instead of reusing the generated one. The goal cannot know the import result, but warns on the
     * triggers it can read from the model.
     */
    @Test
    void modulesThatIntelliJMaySplitAreReported() throws IOException {
        MavenProject levels = application("levels", "com.example.App");
        levels.getProperties().setProperty("maven.compiler.release", "25");
        levels.getProperties().setProperty("maven.compiler.testRelease", "21");

        MavenProject arguments = application("arguments", "com.example.App");
        Plugin compiler = compilerPlugin();
        compiler.setConfiguration(dom("configuration", leaf("testCompilerArgument", "-parameters")));
        arguments.getModel().getBuild().addPlugin(compiler);

        MavenProject executions = application("executions", "com.example.App");
        Plugin split = compilerPlugin();
        split.addExecution(execution("default-compile", "compile",
                dom("configuration", dom("compilerArgs", leaf("arg", "-Xlint")))));
        split.addExecution(execution("default-testCompile", "testCompile", dom("configuration")));
        executions.getModel().getBuild().addPlugin(split);

        Discovery discovery = discover(levels, arguments, executions);

        assertHas(discovery, Level.WARN, "Vidocq idea: IntelliJ may import com.example:levels as the modules"
                + " 'levels.main' and 'levels.test' (testRelease 21 differs from release 25). If Run on its main"
                + " class does not reuse the generated configuration, set"
                + " <vidocq.idea.moduleName>levels.main</vidocq.idea.moduleName> in its pom.");
        assertHasContaining(discovery, Level.WARN, "'arguments.main' and 'arguments.test' (testCompilerArgument is set)");
        assertHasContaining(discovery, Level.WARN,
                "'executions.main' and 'executions.test' (compile and testCompile use different compilerArgs)");
    }

    @Test
    void noSplitWarningWithEqualLevelsOrAnExplicitModuleName() throws IOException {
        MavenProject equal = application("equal", "com.example.App");
        equal.getProperties().setProperty("maven.compiler.release", "25");
        equal.getProperties().setProperty("maven.compiler.testRelease", "25");

        MavenProject named = application("named", "com.example.App");
        named.getProperties().setProperty("maven.compiler.testRelease", "21");
        named.getProperties().setProperty("vidocq.idea.moduleName", "named.main");

        Discovery discovery = discover(equal, named);

        assertTrue(discovery.diagnostics().stream().noneMatch(d -> d.message().contains("IntelliJ may import")),
                discovery.diagnostics().toString());
    }

    @Test
    void activeProfilesThatCarryVidocqSettingsAreReported() throws IOException {
        MavenProject alpha = application("alpha", "com.example.alpha.AlphaApp");
        Profile ide = new Profile();
        ide.setId("ide");
        ide.getProperties().setProperty("vidocq.idea.configurationName", "Alpha (IDE)");
        Profile unrelated = new Profile();
        unrelated.setId("jdk25");
        unrelated.getProperties().setProperty("maven.compiler.release", "25");
        alpha.setActiveProfiles(List.of(ide, unrelated));

        Discovery discovery = discover(alpha);

        assertHas(discovery, Level.INFO, "Vidocq idea: com.example:alpha takes Vidocq settings from active"
                + " profile(s) ide; run vidocq:idea and its check with the same profiles.");
    }

    // ---- fixtures ----

    private Discovery discover(MavenProject... projects) {
        return IdeaApplications.discover(List.of(projects), root, new Properties());
    }

    private MavenProject application(String directory, String mainClass) throws IOException {
        MavenProject project = project(directory);
        project.getModel().getBuild().addPlugin(vidocqPlugin());
        project.getProperties().setProperty("vidocq.mainClass", mainClass);
        return project;
    }

    private MavenProject project(String directory) throws IOException {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId(directory.isEmpty() ? "app" : directory.substring(directory.lastIndexOf('/') + 1));
        model.setVersion("1.0");
        model.setPackaging("jar");
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        Path basedir = Files.createDirectories(root.resolve(directory));
        Path pom = basedir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            Files.writeString(pom, "<project/>");
        }
        project.setFile(pom.toFile());
        return project;
    }

    static Plugin vidocqPlugin() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("io.vidocq.runtime");
        plugin.setArtifactId("vidocq-runtime-maven-plugin");
        return plugin;
    }

    static Plugin compilerPlugin() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        return plugin;
    }

    static PluginExecution execution(String id, String goal, Xpp3Dom configuration) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.addGoal(goal);
        execution.setConfiguration(configuration);
        return execution;
    }

    static Xpp3Dom scanDependencies(String pattern) {
        return dom("configuration", dom("scanDependencies", leaf("scanDependency", pattern)));
    }

    static Xpp3Dom dom(String name, Xpp3Dom... children) {
        Xpp3Dom dom = new Xpp3Dom(name);
        for (Xpp3Dom child : children) {
            dom.addChild(child);
        }
        return dom;
    }

    static Xpp3Dom leaf(String name, String value) {
        Xpp3Dom leaf = new Xpp3Dom(name);
        leaf.setValue(value);
        return leaf;
    }

    private static void assertHas(Discovery discovery, Level level, String message) {
        assertTrue(discovery.diagnostics().contains(new Diagnostic(level, message)),
                "expected " + level + " '" + message + "' in " + discovery.diagnostics());
    }

    private static void assertHasContaining(Discovery discovery, Level level, String fragment) {
        assertTrue(discovery.diagnostics().stream()
                        .anyMatch(d -> d.level() == level && d.message().contains(fragment)),
                "expected " + level + " containing '" + fragment + "' in " + discovery.diagnostics());
    }
}
