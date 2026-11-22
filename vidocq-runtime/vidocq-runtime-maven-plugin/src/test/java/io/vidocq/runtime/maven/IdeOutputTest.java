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
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a project is read by an IDE, and where its classes then go ({@link IdeOutput}). */
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

    @Test
    void theThreeDirectoriesMoveUnderTargetIde() {
        MavenProject project = project("jar");

        assertTrue(IdeOutput.move(project));

        assertMoved(project);
    }

    /** Under an IDE the rule wins over the pom: what the IDE compiles never lands where the build packages from. */
    @Test
    void aPomThatSetsItsOwnDirectoriesIsMovedToo() {
        MavenProject project = project("jar");
        project.getBuild().setDirectory(dir.resolve("build").toString());
        project.getBuild().setOutputDirectory(dir.resolve("out").toString());
        project.getBuild().setTestOutputDirectory(dir.resolve("out-test").toString());

        assertTrue(IdeOutput.move(project));

        assertMoved(project);
    }

    @Test
    void theFolderIgnoresItself() throws IOException {
        IdeOutput.move(project("jar"));

        assertEquals("*\n", Files.readString(dir.resolve("target-ide/.gitignore")));
    }

    /** m2e reads a project again and again: the file is the developer's once it is there. */
    @Test
    void theIgnoreFileIsWrittenOnce() throws IOException {
        MavenProject project = project("jar");
        IdeOutput.move(project);
        Path ignore = dir.resolve("target-ide/.gitignore");
        Files.writeString(ignore, "# mine\n");

        assertTrue(IdeOutput.move(project));

        assertEquals("# mine\n", Files.readString(ignore));
        assertMoved(project);
    }

    /** A model that was never read from a file has no place for the folder. */
    @Test
    void aProjectWithoutAFileIsLeftAlone() {
        Model model = new Model();
        model.setPackaging("jar");
        MavenProject project = new MavenProject(model);

        assertFalse(assertDoesNotThrow(() -> IdeOutput.move(project)));

        assertNull(model.getBuild(), "the model had no build and has none");
    }

    @Test
    void anAggregatorIsLeftAlone() {
        MavenProject project = project("pom");

        assertFalse(IdeOutput.move(project));

        assertNull(project.getBuild().getOutputDirectory());
        assertFalse(Files.exists(dir.resolve("target-ide")));
    }

    @Test
    void theWayOut() {
        MavenProject out = project("jar");
        out.getProperties().setProperty("vidocq.ide.output.skip", " TRUE ");

        assertFalse(IdeOutput.move(out));

        assertNull(out.getBuild().getDirectory());
        assertNull(out.getBuild().getOutputDirectory());
        assertFalse(Files.exists(dir.resolve("target-ide")));

        MavenProject in = project("jar");
        in.getProperties().setProperty("vidocq.ide.output.skip", "no");

        assertTrue(IdeOutput.move(in));

        assertMoved(in);
    }

    /** The folder is the IDE's: a file of the same name in its place fails the IDE's build, not the Maven import. */
    @Test
    void anIgnoreFileThatCannotBeWrittenDoesNotFailTheImport() throws IOException {
        Files.writeString(dir.resolve("target-ide"), "a file, where the folder would be");
        MavenProject project = project("jar");

        assertTrue(assertDoesNotThrow(() -> IdeOutput.move(project)));

        assertMoved(project);
        assertTrue(Files.isRegularFile(dir.resolve("target-ide")), "the file is left as it was");
    }

    @Test
    void ignoringAFolderDoesNotThrowWhenItCannotBeCreated() throws IOException {
        Path file = Files.writeString(dir.resolve("occupied"), "");

        assertDoesNotThrow(() -> IdeOutput.ignore(file.resolve("target-ide")));

        assertFalse(Files.exists(file.resolve("target-ide")));
    }

    private void assertMoved(MavenProject project) {
        Build build = project.getBuild();
        assertEquals(dir.resolve("target-ide").toString(), build.getDirectory());
        assertEquals(dir.resolve("target-ide/classes").toString(), build.getOutputDirectory());
        assertEquals(dir.resolve("target-ide/test-classes").toString(), build.getTestOutputDirectory());
    }

    /** A project read from {@code dir/pom.xml}, with an empty build. */
    private MavenProject project(String packaging) {
        Model model = new Model();
        model.setArtifactId("todo");
        model.setPackaging(packaging);
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve("pom.xml").toFile());
        return project;
    }

    private static Properties properties(String name, String value) {
        Properties properties = new Properties();
        properties.setProperty(name, value);
        return properties;
    }
}
