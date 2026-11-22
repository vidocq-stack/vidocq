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

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code vidocq:vscode} writes into {@code .vscode/}, and what it leaves alone (Vidocq/vidocq#339). */
class VidocqVscodeMojoTest {

    @TempDir
    Path root;

    private final RecordingLog log = new RecordingLog();
    private final List<MavenProject> projects = new ArrayList<>();

    @BeforeEach
    void application() throws IOException {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("todo");
        model.setVersion("1.0.0-SNAPSHOT");
        model.setPackaging("jar");
        model.setBuild(new Build());
        model.getBuild().addPlugin(IdeaApplicationsTest.vidocqPlugin());
        MavenProject project = new MavenProject(model);
        project.getProperties().setProperty("vidocq.mainModule", "com.example.todo");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        project.setFile(root.resolve("pom.xml").toFile());
        projects.add(project);
    }

    @Test
    void writesTheThreeFilesAndTheCheckThenPasses() throws Exception {
        mojo(false).run(projects, projects, new Properties(), root);

        IdeaApplication todo = new IdeaApplication("com.example:todo", null, "todo", "todo", "pom.xml",
                "vidocq:generate", null, 0);
        assertEquals(VscodeFiles.settings("/opt/jdk-25"), read("settings.json"));
        assertEquals(VscodeFiles.tasks(List.of(todo), true), read("tasks.json"));
        assertEquals(VscodeFiles.launch(List.of(todo)), read("launch.json"));
        assertTrue(log.has("INFO", "Vidocq vscode: created .vscode/settings.json"), log.toString());

        mojo(true).run(projects, projects, new Properties(), root);

        assertTrue(log.has("INFO", "Vidocq vscode: .vscode/ is up to date (3 files)."), log.toString());
    }

    @Test
    void aFileItDidNotWriteIsLeftAlone() throws Exception {
        Files.createDirectories(root.resolve(".vscode"));
        String mine = "{\n  \"editor.tabSize\": 2\n}\n";
        Files.writeString(root.resolve(".vscode/settings.json"), mine);

        mojo(false).run(projects, projects, new Properties(), root);

        assertEquals(mine, read("settings.json"));
        assertTrue(log.hasContaining("WARN", "Vidocq vscode: .vscode/settings.json was not written by vidocq:vscode: left"
                + " alone. Add to it:"), log.toString());
        assertTrue(Files.exists(root.resolve(".vscode/tasks.json")));
    }

    @Test
    void theCheckFailsOnAMissingOrChangedFileAndWritesNothing() {
        MojoFailureException missing = assertThrows(MojoFailureException.class,
                () -> mojo(true).run(projects, projects, new Properties(), root));

        assertTrue(missing.getMessage().contains("3 file(s) missing or out of date"), missing.getMessage());
        assertFalse(Files.exists(root.resolve(".vscode")), "the check writes nothing");
    }

    @Test
    void noApplicationWritesNothing() throws Exception {
        projects.get(0).getProperties().remove("vidocq.mainModule");

        mojo(false).run(projects, projects, new Properties(), root);

        assertFalse(Files.exists(root.resolve(".vscode")));
        assertTrue(log.hasContaining("WARN", "Vidocq vscode: no Vidocq application in this build"), log.toString());
    }

    @Test
    void aModuleOfTheReactorIsRefusedForItsRoot() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><modules><module>app</module></modules></project>");
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), "<project/>");

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo(false).run(projects, projects, new Properties(), app));

        assertEquals("Vidocq vscode: " + app.toRealPath() + " is a module of " + root.toRealPath().resolve("pom.xml")
                + ". VS Code usually opens the reactor root, where .vscode/ belongs: run vidocq:vscode from "
                + root.toRealPath() + ".", failure.getMessage());
        assertFalse(Files.exists(app.resolve(".vscode")));
    }

    @Test
    void theGoalRefusesABindingToTheBuild() {
        VidocqVscodeMojo mojo = mojo(false);
        mojo.setMojoExecution(new MojoExecution(new MojoDescriptor(), "vscode-in-build",
                MojoExecution.Source.LIFECYCLE));

        MojoFailureException failure = assertThrows(MojoFailureException.class, mojo::execute);

        assertTrue(failure.getMessage().contains("this goal runs from the command line only (\"mvn vidocq:vscode\")"
                + ", but execution 'vscode-in-build' binds it to the build"), failure.getMessage());
    }

    @Test
    void aFileItWroteIsUpdated() throws Exception {
        mojo(false).run(projects, projects, new Properties(), root);
        Files.writeString(root.resolve(".vscode/settings.json"), VscodeFiles.MARKER + "{}\n");

        mojo(false).run(projects, projects, new Properties(), root);

        assertEquals(VscodeFiles.settings("/opt/jdk-25"), read("settings.json"));
        assertTrue(log.has("INFO", "Vidocq vscode: updated .vscode/settings.json"), log.toString());
    }

    @Test
    void aFileOfOursItNoLongerWritesIsRemovedAndTheCheckReportsIt() throws Exception {
        Path old = Files.createDirectories(root.resolve(".vscode")).resolve("old.json");
        Files.writeString(old, VscodeFiles.MARKER + "{}\n");

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo(true).run(projects, projects, new Properties(), root));
        assertTrue(failure.getMessage().contains("4 file(s) missing or out of date"), failure.getMessage());
        assertTrue(log.has("ERROR", "Vidocq vscode: .vscode/old.json is no longer written"), log.toString());
        assertTrue(Files.exists(old), "the check removes nothing");

        mojo(false).run(projects, projects, new Properties(), root);

        assertFalse(Files.exists(old));
        assertTrue(log.has("INFO", "Vidocq vscode: removed .vscode/old.json"), log.toString());
    }

    @Test
    void aModuleAnIdeStillCompilesIntoTargetIsNamed() throws Exception {
        projects.get(0).getPlugin(IdeaApplications.PLUGIN_KEY).setExtensions(true);
        projects.add(library("todo-model"));

        mojo(false).run(projects, projects, new Properties(), root);

        assertEquals(1, log.lines.stream().filter(line -> line.startsWith("WARN ")).count(), log.toString());
        assertTrue(log.has("WARN", "Vidocq vscode: 1 module of this build is compiled by VS Code into target/classes,"
                + " which Maven then packages: todo-model. Give its parent pom the ide-output profile (documentation,"
                + " \"What an IDE compiles, and where\")."), log.toString());
    }

    @Test
    void severalModulesAreNamedInThePlural() throws Exception {
        projects.get(0).getPlugin(IdeaApplications.PLUGIN_KEY).setExtensions(true);
        projects.add(library("todo-model"));
        projects.add(library("todo-api"));

        mojo(false).run(projects, projects, new Properties(), root);

        assertTrue(log.has("WARN", "Vidocq vscode: 2 modules of this build are compiled by VS Code into"
                + " target/classes, which Maven then packages: todo-model, todo-api. Give their parent pom the"
                + " ide-output profile (documentation, \"What an IDE compiles, and where\")."), log.toString());
    }

    /** Maven loads what moves an application from a build extension only: the attribute is the remedy. */
    @Test
    void anApplicationThatDeclaresThePluginWithoutTheAttributeIsToldToAddIt() throws Exception {
        mojo(false).run(projects, projects, new Properties(), root);

        assertEquals(1, log.lines.stream().filter(line -> line.startsWith("WARN ")).count(), log.toString());
        assertTrue(log.has("WARN", "Vidocq vscode: 1 module of this build declares vidocq-runtime-maven-plugin without"
                + " <extensions>true</extensions>, so VS Code compiles it into target/classes, which Maven then"
                + " packages: todo. Add the attribute to the plugin (documentation, \"What an IDE compiles, and"
                + " where\")."), log.toString());
    }

    @Test
    void severalModulesWithoutTheAttributeAreToldInThePlural() throws Exception {
        MavenProject billing = library("billing");
        billing.getBuild().addPlugin(IdeaApplicationsTest.vidocqPlugin());
        billing.getProperties().setProperty("vidocq.mainModule", "com.example.billing");
        Files.createDirectories(root.resolve("billing"));
        Files.writeString(root.resolve("billing").resolve("pom.xml"), "<project/>");
        projects.add(billing);

        mojo(false).run(projects, projects, new Properties(), root);

        assertTrue(log.has("WARN", "Vidocq vscode: 2 modules of this build declare vidocq-runtime-maven-plugin without"
                + " <extensions>true</extensions>, so VS Code compiles them into target/classes, which Maven then"
                + " packages: todo, billing. Add the attribute to the plugin (documentation, \"What an IDE compiles,"
                + " and where\")."), log.toString());
    }

    /** The check of a CI job names them too: the files are right and the reactor still has the modules. */
    @Test
    void theCheckNamesThemToo() throws Exception {
        projects.get(0).getPlugin(IdeaApplications.PLUGIN_KEY).setExtensions(true);
        projects.add(library("todo-model"));
        mojo(false).run(projects, projects, new Properties(), root);
        log.lines.clear();

        mojo(true).run(projects, projects, new Properties(), root);

        assertTrue(log.hasContaining("WARN", "Maven then packages: todo-model."), log.toString());
        assertTrue(log.has("INFO", "Vidocq vscode: .vscode/ is up to date (3 files)."), log.toString());
    }

    /** A reactor of many libraries: the first ten by name, then how many more; all of them at debug level. */
    @Test
    void aLongListOfModulesIsCut() throws Exception {
        projects.get(0).getPlugin(IdeaApplications.PLUGIN_KEY).setExtensions(true);
        for (int i = 1; i <= 12; i++) {
            projects.add(library("lib-" + i));
        }

        mojo(false).run(projects, projects, new Properties(), root);

        assertTrue(log.has("WARN", "Vidocq vscode: 12 modules of this build are compiled by VS Code into"
                + " target/classes, which Maven then packages: lib-1, lib-2, lib-3, lib-4, lib-5, lib-6, lib-7, lib-8,"
                + " lib-9, lib-10 and 2 more. Give their parent pom the ide-output profile (documentation,"
                + " \"What an IDE compiles, and where\")."), log.toString());
        assertTrue(log.has("DEBUG", "Vidocq vscode: the 12 modules: lib-1, lib-2, lib-3, lib-4, lib-5, lib-6, lib-7,"
                + " lib-8, lib-9, lib-10, lib-11, lib-12"), log.toString());
    }

    /** A list that fits is not said twice. */
    @Test
    void aShortListIsNotRepeatedAtDebugLevel() throws Exception {
        projects.get(0).getPlugin(IdeaApplications.PLUGIN_KEY).setExtensions(true);
        projects.add(library("todo-model"));

        mojo(false).run(projects, projects, new Properties(), root);

        assertFalse(log.hasContaining("DEBUG", "Vidocq vscode: the "), log.toString());
    }

    @Test
    void noWarningWhenEveryModuleIsReached() throws Exception {
        projects.get(0).getPlugin(IdeaApplications.PLUGIN_KEY).setExtensions(true);

        mojo(false).run(projects, projects, new Properties(), root);

        assertFalse(log.lines.stream().anyMatch(line -> line.startsWith("WARN ")), log.toString());
    }

    private MavenProject library(String artifactId) {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId(artifactId);
        model.setVersion("1.0.0-SNAPSHOT");
        model.setPackaging("jar");
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        project.setFile(root.resolve(artifactId).resolve("pom.xml").toFile());
        return project;
    }

    private VidocqVscodeMojo mojo(boolean check) {
        VidocqVscodeMojo mojo = new VidocqVscodeMojo();
        mojo.setLog(log);
        mojo.setCheck(check);
        mojo.setJdk("/opt/jdk-25");
        return mojo;
    }

    private String read(String name) throws IOException {
        return Files.readString(root.resolve(".vscode").resolve(name));
    }
}
