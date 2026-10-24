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

import io.vidocq.runtime.tooling.ProjectScaffolder;
import io.vidocq.runtime.tooling.ProjectSpec;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq:create}, the project {@code vidocq create} writes (Vidocq/vidocq#314). */
class VidocqCreateMojoTest {

    @TempDir
    Path dir;

    private final List<String> infos = new ArrayList<>();

    /** The goal writes the project the CLI writes: the one ProjectScaffolder writes from the same spec. */
    @Test
    void theGoalWritesTheProjectOfTheSharedScaffolder() throws Exception {
        Path byGoal = Files.createDirectories(dir.resolve("goal"));
        Path byScaffolder = Files.createDirectories(dir.resolve("scaffolder"));

        create(byGoal, "todo", "com.acme", null, "cassini-rest, knock-health", false).execute();
        ProjectScaffolder.scaffold(ProjectSpec.of("todo", "com.acme", null, List.of("cassini-rest", "knock-health"),
                null, false, "-"), byScaffolder, "1.2.3", SILENT);

        assertEquals(files(byScaffolder.resolve("todo")), files(byGoal.resolve("todo")));
        for (Path file : files(byScaffolder.resolve("todo"))) {
            assertEquals(Files.readString(byScaffolder.resolve("todo").resolve(file)),
                    Files.readString(byGoal.resolve("todo").resolve(file)), file.toString());
        }
        assertTrue(Files.readString(byGoal.resolve("todo/pom.xml")).contains("<version>1.2.3</version>"));
        assertTrue(infos.contains("Project 'todo' created: cd todo && ./mvnw vidocq:dev"), infos.toString());
    }

    @Test
    void mainWritesTheTrampoline() throws Exception {
        create(dir, "demo", "com.acme", null, null, true).execute();

        assertTrue(Files.isRegularFile(dir.resolve("demo/src/main/java/com/acme/demo/DemoApp.java")));
    }

    @Test
    void withoutNameTheGoalSaysHowToGiveOne() {
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> create(dir, " ", null, null, null, false).execute());

        assertTrue(e.getMessage().contains("-Dname=<name>"), e.getMessage());
    }

    @Test
    void anInvalidPackageNamesTheGoalsOption() {
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> create(dir, "ft-030", null, null, null, false).execute());

        assertTrue(e.getMessage().contains("-Dpackage=<pkg>"), e.getMessage());
    }

    @Test
    void anExistingDirectoryIsLeftAlone() throws Exception {
        Files.createDirectories(dir.resolve("demo"));

        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> create(dir, "demo", null, null, null, false).execute());

        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
    }

    private VidocqCreateMojo create(Path basedir, String name, String groupId, String pkg, String extensions,
                                    boolean main) throws Exception {
        VidocqCreateMojo mojo = new VidocqCreateMojo();
        set(mojo, "basedir", basedir.toFile());
        set(mojo, "name", name);
        set(mojo, "groupId", groupId);
        set(mojo, "pkg", pkg);
        set(mojo, "extensions", extensions);
        set(mojo, "main", main);
        set(mojo, "runtimeVersion", "1.2.3");
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                infos.add(content.toString());
            }
        });
        return mojo;
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(root::relativize).sorted().toList();
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = VidocqCreateMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final Reporter SILENT = new LogReporter(new SystemStreamLog());
}
