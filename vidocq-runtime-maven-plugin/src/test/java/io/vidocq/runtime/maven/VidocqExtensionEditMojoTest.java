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

import io.vidocq.runtime.tooling.ExtensionEditor;
import io.vidocq.runtime.tooling.Reporter;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq:add-extension} and {@code vidocq:remove-extension} (Vidocq/vidocq#314). */
class VidocqExtensionEditMojoTest {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.vidocq.runtime</groupId>
                <artifactId>vidocq-runtime-parent</artifactId>
                <version>9.9.9</version>
              </parent>
              <artifactId>demo</artifactId>
            </project>
            """;

    private static final String MODULE_INFO = "module com.acme.demo {\n}\n";

    private final List<String> infos = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    /** The goal makes the edit the CLI makes: the one ExtensionEditor makes on the same project. */
    @Test
    void addExtensionMakesTheSharedEdit(@TempDir Path dir) throws Exception {
        Path byGoal = project(dir.resolve("goal"));
        Path byEditor = project(dir.resolve("editor"));

        mojo(new VidocqAddExtensionMojo(), byGoal, "cassini-rest, knock-health").execute();
        ExtensionEditor.edit(byEditor, List.of("cassini-rest", "knock-health"), true, "1.2.3", SILENT);

        assertEquals(Files.readString(byEditor.resolve("pom.xml")), Files.readString(byGoal.resolve("pom.xml")));
        assertEquals(Files.readString(moduleInfo(byEditor)), Files.readString(moduleInfo(byGoal)));
        String pom = Files.readString(byGoal.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>"), pom);
        assertTrue(infos.contains("Added cassini-rest"
                + " (io.vidocq.runtime.extensions.jakartaee.core:vidocq-runtime-cassini-rest-extension)"), infos.toString());
        assertTrue(infos.contains("pom.xml updated."), infos.toString());
    }

    @Test
    void removeExtensionUndoesAddExtension(@TempDir Path dir) throws Exception {
        Path project = project(dir);
        mojo(new VidocqAddExtensionMojo(), project, "cassini-rest").execute();

        mojo(new VidocqRemoveExtensionMojo(), project, "cassini-rest").execute();

        assertEquals(POM, Files.readString(project.resolve("pom.xml")));
        assertEquals(MODULE_INFO, Files.readString(moduleInfo(project)));
    }

    @Test
    void addingWhatIsThereChangesNothing(@TempDir Path dir) throws Exception {
        Path project = project(dir);
        mojo(new VidocqAddExtensionMojo(), project, "knock-health").execute();
        infos.clear();

        mojo(new VidocqAddExtensionMojo(), project, "knock-health").execute();

        assertTrue(infos.contains("  • knock-health already present — skipped."), infos.toString());
        assertTrue(infos.contains("No changes — pom.xml left untouched."), infos.toString());
    }

    @Test
    void theRuntimeVersionDefaultsToThePlugin(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project>\n  <artifactId>demo</artifactId>\n</project>\n");

        mojo(new VidocqAddExtensionMojo(), dir, "knock-health").execute();

        assertTrue(Files.readString(dir.resolve("pom.xml")).contains("<version>1.2.3</version>"));
    }

    @Test
    void withoutExtensionsTheGoalSaysHowToNameThem(@TempDir Path dir) throws Exception {
        Path project = project(dir);

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo(new VidocqAddExtensionMojo(), project, " , ").execute());

        assertTrue(failure.getMessage().contains("-Dextensions="), failure.getMessage());
        assertEquals(POM, Files.readString(project.resolve("pom.xml")));
    }

    @Test
    void anErrorFailsTheBuild(@TempDir Path dir) throws Exception {
        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo(new VidocqRemoveExtensionMojo(), dir, "knock-health").execute());

        assertTrue(failure.getMessage().contains("remove-extension"), failure.getMessage());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("No pom.xml in ")), errors.toString());
    }

    private static Path project(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("pom.xml"), POM);
        Files.writeString(moduleInfo(dir), MODULE_INFO);
        return dir;
    }

    private static Path moduleInfo(Path project) {
        return project.resolve("src/main/java/module-info.java");
    }

    private <M extends AbstractExtensionEditMojo> M mojo(M mojo, Path basedir, String extensions) throws Exception {
        set(mojo, "basedir", basedir.toFile());
        set(mojo, "extensions", extensions);
        set(mojo, "runtimeVersion", "1.2.3");
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                infos.add(content.toString());
            }

            @Override
            public void error(CharSequence content) {
                errors.add(content.toString());
            }
        });
        return mojo;
    }

    private static void set(Object mojo, String name, Object value) throws Exception {
        Field field = AbstractExtensionEditMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static final Reporter SILENT = new Reporter() {
        @Override
        public void success(String message, String detail) {
            // The goal's own log is under test, not this one.
        }

        @Override
        public void note(String message) {
            // Same.
        }

        @Override
        public void warning(String message) {
            // Same.
        }

        @Override
        public void error(String message) {
            // Same.
        }
    };
}
