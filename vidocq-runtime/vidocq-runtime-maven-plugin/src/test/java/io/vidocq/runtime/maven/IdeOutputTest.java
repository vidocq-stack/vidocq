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
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a project is read by an IDE, which project then moves, and where its classes go ({@link IdeOutput}). */
class IdeOutputTest {

    @TempDir
    Path dir;

    @Test
    void aSessionThatCarriesM2eVersionInItsUserPropertiesIsAnIdes() {
        assertTrue(IdeOutput.readByAnIde(properties("m2e.version", "2.8.0"), new Properties()));
    }

    /** The value does not matter: the property is there or it is not. */
    @Test
    void orInItsSystemProperties() {
        assertTrue(IdeOutput.readByAnIde(new Properties(), properties("m2e.version", "")));
    }

    @Test
    void theCommandLineIsNotAnIde() {
        assertFalse(IdeOutput.readByAnIde(new Properties(), new Properties()));
        assertFalse(IdeOutput.readByAnIde(null, null));
    }

    /**
     * IntelliJ IDEA's Maven import runs the participant too and compiles into its own {@code out/} or into
     * {@code target/} as the developer set it: it gives {@code idea.version} and {@code idea.maven.embedder.version},
     * never m2e's property (read in the Maven plugin of IntelliJ IDEA 2026.3, build 263.7307.200, on 2026-11-22).
     */
    @Test
    void intelliJsImportIsNotRead() {
        Properties idea = properties("idea.version", "2026.3");
        idea.setProperty("idea.maven.embedder.version", "2026.3");

        assertFalse(IdeOutput.readByAnIde(idea, idea));
    }

    /** An application of one module: the plugin in its pom, no parent pom next to it, nothing that reads it. */
    @Test
    void aStandaloneApplicationMoves() {
        MavenProject application = project("todo", "jar", true);

        assertTrue(IdeOutput.moves(application, List.of(application)));
    }

    /** The plugin moves what declares it: a library is not its own to move. */
    @Test
    void aProjectThatDoesNotDeclareThePluginDoesNot() {
        MavenProject library = project("todo-model", "jar", false);

        assertFalse(IdeOutput.moves(library, List.of(library)));
    }

    @Test
    void aProjectWithoutABuildDoesNot() {
        Model model = new Model();
        model.setPackaging("jar");
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve("pom.xml").toFile());

        assertFalse(assertDoesNotThrow(() -> IdeOutput.moves(project, List.of(project))));
    }

    /** A model that was never read from a file has no place for the folder. */
    @Test
    void aProjectWithoutAFileDoesNot() {
        MavenProject project = project("todo", "jar", true);
        project.setFile(null);

        assertFalse(assertDoesNotThrow(() -> IdeOutput.moves(project, List.of(project))));
    }

    @Test
    void anAggregatorDoesNot() {
        MavenProject aggregator = project("todo", "pom", true);

        assertFalse(IdeOutput.moves(aggregator, List.of(aggregator)));
    }

    @Test
    void theWayOut() {
        MavenProject out = project("todo", "jar", true);
        out.getProperties().setProperty("vidocq.ide.output.skip", " TRUE ");

        assertFalse(IdeOutput.moves(out, List.of(out)));
        assertTrue(IdeOutput.takesTheWayOut(out));

        MavenProject in = project("todo", "jar", true);
        in.getProperties().setProperty("vidocq.ide.output.skip", "no");

        assertTrue(IdeOutput.moves(in, List.of(in)));
        assertFalse(IdeOutput.takesTheWayOut(in));
    }

    /**
     * A module of a build of several modules is read by the others: m2e keeps Maven's folders in its own record for
     * a project the participant moves, and a test of the application would run the library Maven built last. Its
     * parent pom, read from the disk, is what says it is such a module.
     */
    @Test
    void aModuleWhoseParentPomIsOnDiskDoesNot() {
        MavenProject module = project("todo", "jar", true);
        module.setParentFile(dir.resolve("pom.xml").toFile());

        assertFalse(IdeOutput.moves(module, List.of(module)));
    }

    @Test
    void aProjectThatListsModulesDoesNot() {
        MavenProject project = project("todo", "jar", true);
        project.getModel().addModule("todo-model");

        assertFalse(IdeOutput.moves(project, List.of(project)));
    }

    @Test
    void aProjectAnotherOneOfTheSessionDependsOnDoesNot() {
        MavenProject application = project("todo", "jar", true);
        MavenProject tests = project("todo-it", "jar", false);
        tests.getModel().addDependency(dependency("com.example", "todo"));

        assertFalse(IdeOutput.moves(application, List.of(application, tests)));
        assertTrue(IdeOutput.moves(application, List.of(application)), "read without it, the application moves");
    }

    /** Both the groupId and the artifactId name a project. */
    @Test
    void aDependencyOnAnotherArtifactDoesNotCount() {
        MavenProject application = project("todo", "jar", true);
        MavenProject other = project("billing", "jar", true);
        other.getModel().addDependency(dependency("com.example", "todo-model"));
        other.getModel().addDependency(dependency("org.other", "todo"));

        assertTrue(IdeOutput.moves(application, List.of(application, other)));
        assertTrue(IdeOutput.moves(other, List.of(application, other)));
    }

    /** m2e reads a project again and again: the answer does not depend on where the project already is. */
    @Test
    void askedAgainOnceTheProjectIsMovedTheAnswerIsTheSame() {
        MavenProject application = project("todo", "jar", true);
        List<MavenProject> session = List.of(application);
        assertTrue(IdeOutput.moves(application, session));
        IdeOutput.move(application);

        assertTrue(IdeOutput.moves(application, session));
        IdeOutput.move(application);

        assertMoved(application, "todo");
    }

    @Test
    void theThreeDirectoriesMoveUnderTargetIde() {
        MavenProject project = project("todo", "jar", true);

        IdeOutput.move(project);

        assertMoved(project, "todo");
    }

    /** Under an IDE the rule wins over the pom: what the IDE compiles never lands where the build packages from. */
    @Test
    void aPomThatSetsItsOwnDirectoriesIsMovedToo() {
        MavenProject project = project("todo", "jar", true);
        project.getBuild().setDirectory(dir.resolve("build").toString());
        project.getBuild().setOutputDirectory(dir.resolve("out").toString());
        project.getBuild().setTestOutputDirectory(dir.resolve("out-test").toString());

        IdeOutput.move(project);

        assertMoved(project, "todo");
    }

    @Test
    void theFolderIgnoresItself() throws IOException {
        IdeOutput.move(project("todo", "jar", true));

        assertEquals("*\n", Files.readString(dir.resolve("todo/target-ide/.gitignore")));
    }

    /** m2e reads a project again and again: the file is the developer's once it is there. */
    @Test
    void theIgnoreFileIsWrittenOnce() throws IOException {
        MavenProject project = project("todo", "jar", true);
        IdeOutput.move(project);
        Path ignore = dir.resolve("todo/target-ide/.gitignore");
        Files.writeString(ignore, "# mine\n");

        IdeOutput.move(project);

        assertEquals("# mine\n", Files.readString(ignore));
        assertMoved(project, "todo");
    }

    /** The folder is the IDE's: a file of the same name in its place fails the IDE's build, not the Maven import. */
    @Test
    void anIgnoreFileThatCannotBeWrittenDoesNotFailTheImport() throws IOException {
        Files.createDirectories(dir.resolve("todo"));
        Files.writeString(dir.resolve("todo/target-ide"), "a file, where the folder would be");
        MavenProject project = project("todo", "jar", true);

        assertDoesNotThrow(() -> IdeOutput.move(project));

        assertMoved(project, "todo");
        assertTrue(Files.isRegularFile(dir.resolve("todo/target-ide")), "the file is left as it was");
    }

    @Test
    void ignoringAFolderDoesNotThrowWhenItCannotBeCreated() throws IOException {
        Path file = Files.writeString(dir.resolve("occupied"), "");

        assertDoesNotThrow(() -> IdeOutput.ignore(file.resolve("target-ide")));

        assertFalse(Files.exists(file.resolve("target-ide")));
    }

    private void assertMoved(MavenProject project, String folder) {
        Build build = project.getBuild();
        assertEquals(dir.resolve(folder + "/target-ide").toString(), build.getDirectory());
        assertEquals(dir.resolve(folder + "/target-ide/classes").toString(), build.getOutputDirectory());
        assertEquals(dir.resolve(folder + "/target-ide/test-classes").toString(), build.getTestOutputDirectory());
    }

    /**
     * A project read from {@code dir/<artifactId>/pom.xml}, with no parent pom on disk and no module, and with the
     * plugin in its build or without it.
     */
    private MavenProject project(String artifactId, String packaging, boolean withThePlugin) {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId(artifactId);
        model.setPackaging(packaging);
        model.setBuild(new Build());
        if (withThePlugin) {
            Plugin vidocq = new Plugin();
            vidocq.setGroupId("io.vidocq.runtime");
            vidocq.setArtifactId("vidocq-runtime-maven-plugin");
            model.getBuild().addPlugin(vidocq);
        }
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve(artifactId).resolve("pom.xml").toFile());
        return project;
    }

    private static Dependency dependency(String groupId, String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0");
        return dependency;
    }

    private static Properties properties(String name, String value) {
        Properties properties = new Properties();
        properties.setProperty(name, value);
        return properties;
    }
}
