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

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How Maven finds the participant, and what it does to the projects of a session (Vidocq/vidocq#339). */
class CodegenPathsParticipantTest {

    /** {@code target} as a path segment of its own: {@code target/classes}, {@code ${basedir}/target}. */
    private static final Pattern MAVENS_FOLDER = Pattern.compile("(?<![\\w.-])target(?![\\w.-])");

    @TempDir
    Path dir;

    /**
     * A Plexus descriptor, not a Sisu index: Maven 3.9.9 reads an index with an ASM that does not know Java 25 class
     * files, and the participant never ran there.
     */
    @Test
    void thePluginDeclaresTheParticipantInAPlexusDescriptor() throws Exception {
        // This module's own output: Maven's jars on the test class path carry Sisu indexes of their own.
        assertFalse(Files.exists(Path.of("target/classes/META-INF/sisu/javax.inject.Named")),
                "a Sisu index is skipped by Maven 3.9.9 for Java 25 classes");
        Element component;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/plexus/components.xml")) {
            assertNotNull(in, "the plugin declares no Plexus component");
            component = (Element) DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in)
                    .getElementsByTagName("component").item(0);
        }
        assertEquals(AbstractMavenLifecycleParticipant.class.getName(), text(component, "role"));
        Class<?> implementation = Class.forName(text(component, "implementation"));
        assertEquals(CodegenPathsParticipant.class, implementation);
        assertTrue(AbstractMavenLifecycleParticipant.class.isAssignableFrom(implementation));
    }

    /**
     * VS Code's Java extension runs m2e on the Java 21 runtime it ships, and so may Eclipse: a participant compiled for
     * Java 25 failed the whole Maven import there ({@code TypeNotPresentException: Type CodegenPathsParticipant not
     * present}). Its classes are Java 17 class files, and name nothing of {@code vidocq-runtime-tooling} (Java 25).
     */
    @Test
    void theParticipantLoadsOnTheJava17RuntimeOfAnIde() throws Exception {
        List<Path> classes;
        try (var files = Files.list(Path.of("target/classes/io/vidocq/runtime/maven"))) {
            classes = files.filter(f -> f.getFileName().toString().startsWith("CodegenPaths")
                    || f.getFileName().toString().startsWith("ApplicationBuild")
                    || f.getFileName().toString().startsWith("IdeOutput")).toList();
        }
        // CodegenPaths, CodegenPathsParticipant, ApplicationBuild, its nested Execution record, and IdeOutput.
        assertEquals(5, classes.size(), classes.toString());
        for (Path file : classes) {
            ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
            assertTrue(model.majorVersion() <= ClassFile.JAVA_17_VERSION,
                    file.getFileName() + " is a Java " + (model.majorVersion() - 44) + " class file");
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof ClassEntry type) {
                    assertFalse(type.asInternalName().startsWith("io/vidocq/runtime/tooling/"),
                            file.getFileName() + " names " + type.asInternalName());
                }
            }
        }
    }

    @Test
    void afterProjectsReadWiresEveryProjectOfTheSession() {
        Model model = new Model();
        model.setBuild(new org.apache.maven.model.Build());
        org.apache.maven.model.Plugin vidocq = new org.apache.maven.model.Plugin();
        vidocq.setGroupId("io.vidocq.runtime");
        vidocq.setArtifactId("vidocq-runtime-maven-plugin");
        model.getBuild().addPlugin(vidocq);
        org.apache.maven.model.Plugin compiler = new org.apache.maven.model.Plugin();
        compiler.setArtifactId("maven-compiler-plugin");
        model.getBuild().addPlugin(compiler);
        Dependency core = new Dependency();
        core.setGroupId("io.vidocq.runtime");
        core.setArtifactId("vidocq-runtime-core");
        core.setVersion("0.4.0");
        model.addDependency(core);
        MavenSession session = session(List.of(new MavenProject(model)));

        new CodegenPathsParticipant().afterProjectsRead(session);

        assertNotNull(compiler.getConfiguration(), "the indexer was not wired");
    }

    /** Vidocq/vidocq#380: a project without the parent gets the parent's build from the plugin it declares. */
    @Test
    void afterProjectsReadCompletesTheBuildOfAProjectWithoutTheParent() {
        Model model = new Model();
        model.setBuild(new org.apache.maven.model.Build());
        org.apache.maven.model.Plugin vidocq = new org.apache.maven.model.Plugin();
        vidocq.setGroupId("io.vidocq.runtime");
        vidocq.setArtifactId("vidocq-runtime-maven-plugin");
        vidocq.setExtensions(true);
        model.getBuild().addPlugin(vidocq);
        MavenProject project = new MavenProject(model);
        MavenSession session = session(List.of(project));

        new CodegenPathsParticipant().afterProjectsRead(session);

        // The parent's six, the build identity among them.
        assertEquals(6, vidocq.getExecutions().size(), vidocq.getExecutions().toString());
        assertEquals("25", model.getProperties().getProperty("maven.compiler.release"));
        // What was added is left for checkpom, which asks the pom for what an IDE reads there (the release).
        assertTrue(project.getContextValue(ApplicationBuild.CONTEXT_KEY) instanceof List<?> added
                && added.contains("property maven.compiler.release=25"),
                String.valueOf(project.getContextValue(ApplicationBuild.CONTEXT_KEY)));
    }

    /**
     * Read by m2e, which sets {@code m2e.version} on its session, an application of one module compiles into a
     * folder of its own ({@link IdeOutput}).
     */
    @Test
    void anIdeGetsItsOwnOutputFolderForAStandaloneApplication() {
        MavenProject application = project("todo", true);

        new CodegenPathsParticipant().afterProjectsRead(session(List.of(application), m2e(), new Properties()));

        assertEquals(dir.resolve("todo/target-ide").toString(), application.getBuild().getDirectory());
        assertEquals(dir.resolve("todo/target-ide/classes").toString(), application.getBuild().getOutputDirectory());
        assertEquals(dir.resolve("todo/target-ide/test-classes").toString(),
                application.getBuild().getTestOutputDirectory());
        assertTrue(Files.isRegularFile(dir.resolve("todo/target-ide/.gitignore")), "the folder ignores itself");
    }

    @Test
    void alsoWhenTheSystemPropertiesCarryIt() {
        MavenProject application = project("todo", true);

        new CodegenPathsParticipant().afterProjectsRead(session(List.of(application), new Properties(), m2e()));

        assertEquals(dir.resolve("todo/target-ide/classes").toString(), application.getBuild().getOutputDirectory());
    }

    /**
     * The application of two modules that was run in the language server of VS Code's Java extension on 2026-11-22:
     * an aggregator that is the parent of both, a library without the plugin, the application with it. When the
     * participant moved them, the IDE compiled the library into its {@code target-ide/} and a test of the
     * application still ran the library of {@code target/classes}: m2e keeps Maven's folders in its own record for
     * a project the participant moves, and gives a launch its workspace dependencies from that record. So nothing
     * of such a build moves; its parent pom takes the {@code ide-output} profile, which acts while the model is
     * built.
     */
    @Test
    void theModulesOfAnApplicationOfSeveralModulesKeepMavensFolders() {
        MavenProject parent = aggregator("todo-parent", false, "todo-lib", "todo-app");
        MavenProject library = module(parent, "todo-lib", false);
        MavenProject application = module(parent, "todo-app", true);
        application.getModel().addDependency(dependency("com.example", "todo-lib"));

        new CodegenPathsParticipant()
                .afterProjectsRead(session(List.of(parent, library, application), m2e(), new Properties()));

        assertInMavensFolders(parent, library, application);
    }

    /** The same when every module inherits the plugin from the parent pom, and none depends on another. */
    @Test
    void theModulesThatInheritThePluginFromTheirParentKeepMavensFolders() {
        MavenProject parent = aggregator("shop-parent", true, "shop-catalog", "shop-orders");
        MavenProject catalog = module(parent, "shop-catalog", true);
        MavenProject orders = module(parent, "shop-orders", true);

        new CodegenPathsParticipant()
                .afterProjectsRead(session(List.of(parent, catalog, orders), m2e(), new Properties()));

        assertInMavensFolders(parent, catalog, orders);
    }

    /** Two projects with no pom in common, read together: the one the other depends on is read through the record. */
    @Test
    void aStandaloneApplicationAnotherProjectDependsOnKeepsMavensFolders() {
        MavenProject application = project("todo", true);
        MavenProject acceptance = project("todo-acceptance", true);
        acceptance.getModel().addDependency(dependency("com.example", "todo"));

        new CodegenPathsParticipant()
                .afterProjectsRead(session(List.of(application, acceptance), m2e(), new Properties()));

        assertInMavensFolders(application);
        assertEquals(dir.resolve("todo-acceptance/target-ide/classes").toString(),
                acceptance.getBuild().getOutputDirectory());
    }

    /** m2e reads the projects again when a pom changes: each one stays where the first read put it. */
    @Test
    void readAgainEveryProjectIsWhereItWas() {
        MavenProject application = project("todo", true);
        MavenProject acceptance = project("todo-acceptance", true);
        acceptance.getModel().addDependency(dependency("com.example", "todo"));
        MavenProject alone = project("billing", true);
        MavenSession session = session(List.of(application, acceptance, alone), m2e(), new Properties());
        CodegenPathsParticipant participant = new CodegenPathsParticipant();
        participant.afterProjectsRead(session);

        participant.afterProjectsRead(session);

        assertInMavensFolders(application);
        assertEquals(dir.resolve("todo-acceptance/target-ide/classes").toString(),
                acceptance.getBuild().getOutputDirectory());
        assertEquals(dir.resolve("billing/target-ide").toString(), alone.getBuild().getDirectory());
        assertEquals(dir.resolve("billing/target-ide/classes").toString(), alone.getBuild().getOutputDirectory());
        assertEquals(dir.resolve("billing/target-ide/test-classes").toString(),
                alone.getBuild().getTestOutputDirectory());
    }

    /** A Maven build, on the command line, in CI or started from an IDE, builds where it always did. */
    @Test
    void theCommandLineKeepsMavensFolders() {
        MavenProject application = project("todo", true);

        new CodegenPathsParticipant().afterProjectsRead(session(List.of(application)));

        assertInMavensFolders(application);
    }

    /** IntelliJ IDEA's import runs the participant and names itself otherwise: its output folders are its own. */
    @Test
    void intelliJsImportKeepsMavensFolders() {
        MavenProject application = project("todo", true);
        Properties idea = new Properties();
        idea.setProperty("idea.version", "2026.3");
        idea.setProperty("idea.maven.embedder.version", "2026.3");

        new CodegenPathsParticipant().afterProjectsRead(session(List.of(application), idea, idea));

        assertInMavensFolders(application);
    }

    /**
     * What the participant adds to a project without the parent is evaluated when a goal runs, so it follows the
     * folder: a configuration that named Maven's folder would send the IDE back into it. The project is as Maven
     * hands it over, its build directories interpolated.
     */
    @Test
    void nothingTheParticipantAddsNamesMavensFolder() {
        MavenProject application = project("todo", true);
        application.getBuild().addPlugin(plugin("org.apache.maven.plugins", "maven-compiler-plugin"));
        application.getBuild().addPlugin(plugin("org.apache.maven.plugins", "maven-surefire-plugin"));
        String mavens = application.getBuild().getDirectory();

        new CodegenPathsParticipant().afterProjectsRead(session(List.of(application), m2e(), new Properties()));

        assertNotNull(application.getContextValue(ApplicationBuild.CONTEXT_KEY), "the build was not completed");
        assertEquals(dir.resolve("todo/target-ide").toString(), application.getBuild().getDirectory());
        assertEquals(List.of(), namingMavensFolder(application, mavens));
    }

    /**
     * The test above can fail: a configuration or a property is seen when it names Maven's folder by its path, from
     * the project's folder in either spelling, or relatively; what follows the build directory is not.
     */
    @Test
    void aConfigurationThatNamesMavensFolderIsSeen() {
        String mavens = dir.resolve("todo/target").toString();
        for (String value : List.of(mavens, mavens + File.separator + "surefire-reports",
                "${project.basedir}/target/surefire-reports", "${basedir}/target", "target/surefire-reports",
                "-Dreports=target/surefire-reports", "./target")) {
            MavenProject configured = project("todo", true);
            Plugin surefire = plugin("org.apache.maven.plugins", "maven-surefire-plugin");
            Xpp3Dom configuration = new Xpp3Dom("configuration");
            Xpp3Dom reports = new Xpp3Dom("reportsDirectory");
            reports.setValue(value);
            configuration.addChild(reports);
            surefire.setConfiguration(configuration);
            configured.getBuild().addPlugin(surefire);
            assertEquals(List.of("maven-surefire-plugin reportsDirectory=" + value),
                    namingMavensFolder(configured, mavens));

            MavenProject withAProperty = project("todo", true);
            withAProperty.getProperties().setProperty("reports", value);
            assertEquals(List.of("property reports=" + value), namingMavensFolder(withAProperty, mavens));
        }
        for (String value : List.of(dir.resolve("todo/target-ide/classes").toString(),
                "${project.build.directory}/surefire-reports", "${project.build.outputDirectory}",
                "target-ide/classes", "${maven.compiler.target}", "targets/x", "25")) {
            MavenProject following = project("todo", true);
            following.getProperties().setProperty("reports", value);
            assertEquals(List.of(), namingMavensFolder(following, mavens), value);
        }
    }

    /** The plugin configurations, those of their executions, and the properties of {@code project} that name it. */
    private static List<String> namingMavensFolder(MavenProject project, String mavens) {
        List<String> named = new ArrayList<>();
        for (Plugin plugin : project.getBuild().getPlugins()) {
            collect(plugin.getConfiguration(), plugin.getArtifactId(), mavens, named);
            for (PluginExecution execution : plugin.getExecutions()) {
                collect(execution.getConfiguration(), plugin.getArtifactId() + "#" + execution.getId(), mavens, named);
            }
        }
        project.getProperties().forEach((name, value) -> {
            if (names(String.valueOf(value), mavens)) {
                named.add("property " + name + "=" + value);
            }
        });
        return named;
    }

    /**
     * Whether {@code value} names the folder {@code mavens}, by its absolute path or as a {@code target} segment
     * ({@code ${project.basedir}/target}, {@code ${basedir}/target}, {@code target/…}), and not a folder whose name
     * only starts as it does.
     */
    private static boolean names(String value, String mavens) {
        return value.endsWith(mavens) || value.contains(mavens + File.separator) || value.contains(mavens + "/")
                || MAVENS_FOLDER.matcher(value).find();
    }

    private static void collect(Object configuration, String where, String mavens, List<String> named) {
        if (configuration instanceof Xpp3Dom dom) {
            if (dom.getValue() != null && names(dom.getValue(), mavens)) {
                named.add(where + " " + dom.getName() + "=" + dom.getValue());
            }
            for (String attribute : dom.getAttributeNames()) {
                if (names(dom.getAttribute(attribute), mavens)) {
                    named.add(where + " " + dom.getName() + "@" + attribute + "=" + dom.getAttribute(attribute));
                }
            }
            for (Xpp3Dom child : dom.getChildren()) {
                collect(child, where, mavens, named);
            }
        }
    }

    /** Each of {@code projects} has the three directories Maven gave it, and no folder of an IDE. */
    private static void assertInMavensFolders(MavenProject... projects) {
        for (MavenProject project : projects) {
            Path basedir = project.getBasedir().toPath();
            String name = project.getArtifactId();
            assertEquals(basedir.resolve("target").toString(), project.getBuild().getDirectory(), name);
            assertEquals(basedir.resolve("target/classes").toString(), project.getBuild().getOutputDirectory(), name);
            assertEquals(basedir.resolve("target/test-classes").toString(),
                    project.getBuild().getTestOutputDirectory(), name);
            assertFalse(Files.exists(basedir.resolve("target-ide")), name + " has a target-ide folder");
        }
    }

    /**
     * A project read from {@code dir/<artifactId>/pom.xml}, as Maven hands it over (its build directories
     * interpolated), with no parent pom on disk, and with the plugin as a build extension or without it.
     */
    private MavenProject project(String artifactId, boolean withThePlugin) {
        return project(dir.resolve(artifactId), artifactId, "jar", withThePlugin);
    }

    /** The pom of a build of several modules, in {@code dir/<artifactId>}, with the plugin or without it. */
    private MavenProject aggregator(String artifactId, boolean withThePlugin, String... modules) {
        MavenProject aggregator = project(dir.resolve(artifactId), artifactId, "pom", withThePlugin);
        for (String module : modules) {
            aggregator.getModel().addModule(module);
        }
        return aggregator;
    }

    /** A module in the folder of {@code parent}, whose pom it inherits from the disk. */
    private static MavenProject module(MavenProject parent, String artifactId, boolean withThePlugin) {
        MavenProject module = project(parent.getBasedir().toPath().resolve(artifactId), artifactId, "jar",
                withThePlugin);
        module.setParent(parent);
        module.setParentFile(parent.getFile());
        return module;
    }

    private static MavenProject project(Path folder, String artifactId, String packaging, boolean withThePlugin) {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId(artifactId);
        model.setPackaging(packaging);
        Build build = new Build();
        build.setDirectory(folder.resolve("target").toString());
        build.setOutputDirectory(folder.resolve("target/classes").toString());
        build.setTestOutputDirectory(folder.resolve("target/test-classes").toString());
        model.setBuild(build);
        if (withThePlugin) {
            Plugin vidocq = plugin("io.vidocq.runtime", "vidocq-runtime-maven-plugin");
            vidocq.setExtensions(true);
            model.getBuild().addPlugin(vidocq);
        }
        MavenProject project = new MavenProject(model);
        project.setFile(folder.resolve("pom.xml").toFile());
        return project;
    }

    private static Plugin plugin(String groupId, String artifactId) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        return plugin;
    }

    private static Dependency dependency(String groupId, String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0");
        return dependency;
    }

    /** The properties of a session of m2e. */
    private static Properties m2e() {
        Properties properties = new Properties();
        properties.setProperty("m2e.version", "2.8.0");
        return properties;
    }

    /** A session of {@code projects} as the command line gives it: no property of an IDE. */
    private static MavenSession session(List<MavenProject> projects) {
        return session(projects, new Properties(), new Properties());
    }

    /** A session of {@code projects} and of the properties the participant reads, which is all it reads. */
    @SuppressWarnings("deprecation")
    private static MavenSession session(List<MavenProject> projects, Properties user, Properties system) {
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setUserProperties(user);
        request.setSystemProperties(system);
        MavenSession session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        session.setProjects(projects);
        return session;
    }

    private static String text(Element parent, String name) {
        return parent.getElementsByTagName(name).item(0).getTextContent().strip();
    }
}
