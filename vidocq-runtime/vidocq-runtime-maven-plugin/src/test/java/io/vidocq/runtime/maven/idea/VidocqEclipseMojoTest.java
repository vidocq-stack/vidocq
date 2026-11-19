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
import org.apache.maven.plugin.MojoFailureException;
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

/** What {@code vidocq:eclipse} writes into {@code .eclipse/}, and what it leaves alone (Vidocq/vidocq#339). */
class VidocqEclipseMojoTest {

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
        project.setFile(root.resolve("pom.xml").toFile());
        projects.add(project);
    }

    @Test
    void writesTheFourConfigurationsSaysWhatItCannotSetAndTheCheckThenPasses() throws Exception {
        mojo(false).run(projects, projects, new Properties(), root);

        IdeaApplication todo = new IdeaApplication("com.example:todo", null, "todo", "todo", "pom.xml",
                "vidocq:generate", null, 0);
        EclipseFiles.launches(List.of(todo)).forEach((name, content) -> assertEquals(content, read(name), name));
        assertTrue(log.has("INFO", "Vidocq eclipse: created .eclipse/todo (dev).launch"), log.toString());
        assertTrue(log.hasContaining("INFO", "Turn Project > Build Automatically off while vidocq:dev runs"),
                log.toString());

        mojo(true).run(projects, projects, new Properties(), root);

        assertTrue(log.has("INFO", "Vidocq eclipse: .eclipse/ is up to date (4 files)."), log.toString());
    }

    @Test
    void aConfigurationItNoLongerWritesIsRemovedAndAnyOtherFileIsLeftAlone() throws Exception {
        Path folder = Files.createDirectories(root.resolve(".eclipse"));
        Files.writeString(folder.resolve("old (dev).launch"), EclipseFiles.MARKER + "<launchConfiguration/>\n");
        String mine = "<?xml version=\"1.0\"?>\n<launchConfiguration/>\n";
        Files.writeString(folder.resolve("mine.launch"), mine);
        Files.writeString(folder.resolve("todo (run).launch"), mine);

        mojo(false).run(projects, projects, new Properties(), root);

        assertFalse(Files.exists(folder.resolve("old (dev).launch")));
        assertTrue(log.has("INFO", "Vidocq eclipse: removed .eclipse/old (dev).launch"), log.toString());
        assertEquals(mine, read("mine.launch"));
        assertEquals(mine, read("todo (run).launch"));
        assertTrue(log.hasContaining("WARN", "Vidocq eclipse: .eclipse/todo (run).launch was not written by"
                + " vidocq:eclipse: left alone."), log.toString());
    }

    @Test
    void theCheckFailsOnAMissingOrStaleFileAndWritesNothing() throws Exception {
        Path folder = Files.createDirectories(root.resolve(".eclipse"));
        Files.writeString(folder.resolve("old (dev).launch"), EclipseFiles.MARKER + "<launchConfiguration/>\n");

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo(true).run(projects, projects, new Properties(), root));

        assertTrue(failure.getMessage().contains("5 file(s) missing or out of date"), failure.getMessage());
        assertTrue(log.has("ERROR", "Vidocq eclipse: .eclipse/old (dev).launch is no longer written"), log.toString());
        assertTrue(Files.exists(folder.resolve("old (dev).launch")), "the check removes nothing");
        assertFalse(Files.exists(folder.resolve("todo (dev).launch")), "the check writes nothing");
    }

    private VidocqEclipseMojo mojo(boolean check) {
        VidocqEclipseMojo mojo = new VidocqEclipseMojo();
        mojo.setLog(log);
        mojo.setCheck(check);
        return mojo;
    }

    private String read(String name) {
        try {
            return Files.readString(root.resolve(".eclipse").resolve(name));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
