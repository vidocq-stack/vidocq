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
package io.vidocq.runtime.cli;

import io.vidocq.runtime.cli.ext.ProjectExtensions;
import io.vidocq.runtime.cli.ext.ResolvedArtifact;
import io.vidocq.runtime.tooling.DoctorFacts;
import io.vidocq.runtime.tooling.ProjectSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#339 (evolution 3): {@code vidocq doctor} checks the project, and {@code --fix} repairs it. */
class CommandRunnerDoctorTest {

    private static Path project(Path dir) throws IOException {
        CommandRunner.runCreate(new Command.Create(new ProjectSpec("demo", "com.acme", "com.acme.demo",
                Set.of("rest-cassini"), null, false)), dir);
        Path project = dir.resolve("demo");
        Path moduleInfo = project.resolve("src/main/java/module-info.java");
        Files.writeString(moduleInfo, Files.readString(moduleInfo).lines()
                .filter(l -> !l.contains("jakarta.ws.rs")).collect(Collectors.joining("\n", "", "\n")));
        return project;
    }

    private static int doctor(Path project, boolean fix, StringBuilder out) {
        out.append(captured(() -> out.append("exit=").append(CommandRunner.runDoctor(new Command.Doctor(false, fix),
                project, () -> DoctorFacts.Extensions.found(1))).append('\n')));
        return out.toString().contains("exit=0") ? 0 : 1;
    }

    @Test
    void aBrokenProjectFailsAndTheReportNamesTheFix(@TempDir Path dir) throws IOException {
        StringBuilder out = new StringBuilder();

        assertEquals(1, doctor(project(dir), false, out));

        assertTrue(out.toString().contains("Module directives"), out.toString());
        assertTrue(out.toString().contains("rest-cassini: requires jakarta.ws.rs"), out.toString());
        assertTrue(out.toString().contains("Fix: vidocq doctor --fix"), out.toString());
    }

    @Test
    void fixRepairsTheProjectThenReportsIt(@TempDir Path dir) throws IOException {
        Path project = project(dir);
        StringBuilder out = new StringBuilder();

        assertEquals(0, doctor(project, true, out));

        assertTrue(Files.readString(project.resolve("src/main/java/module-info.java"))
                .contains("requires jakarta.ws.rs; // vidocq:rest-cassini"));
        assertTrue(out.toString().contains("module-info.java updated"), out.toString());
        assertFalse(out.toString().contains("Fix: vidocq doctor --fix"), out.toString());
    }

    @Test
    void aHealthyProjectHasNothingToFix(@TempDir Path dir) {
        CommandRunner.runCreate(new Command.Create(new ProjectSpec("demo", "com.acme", "com.acme.demo",
                Set.of("rest-cassini"), null, false)), dir);
        StringBuilder out = new StringBuilder();

        assertEquals(0, doctor(dir.resolve("demo"), true, out));

        assertTrue(out.toString().contains("Nothing to fix."), out.toString());
    }

    /** A project created healthy, whose {@code vidocq.properties} gives {@code vidocq.sample.port} this value. */
    private static Path sampleProject(Path dir, String value) throws IOException {
        CommandRunner.runCreate(new Command.Create(new ProjectSpec("demo", "com.acme", "com.acme.demo",
                Set.of("rest-cassini"), null, false)), dir);
        Path config = dir.resolve("demo/src/main/resources/vidocq.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "\nvidocq.sample.port=" + value + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return dir.resolve("demo");
    }

    /** A jar that describes {@code vidocq.sample.port} as an int. */
    private static Path sampleJar(Path dir) throws IOException {
        Path jar = dir.resolve("sample.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/vidocq/config-user-keys.properties"));
            zip.write("format=1\nvidocq.sample.port|type=int\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    private static int doctor(Path project, StringBuilder out, Supplier<DoctorFacts.Extensions> extensions) {
        int[] exit = new int[1];
        out.append(captured(() -> exit[0] = CommandRunner.runDoctor(new Command.Doctor(false, false), project,
                extensions)));
        return exit[0];
    }

    @Test
    void aValueThatIsNotOfTheTypeOfItsKeyIsAWarningAndNotAFailure(@TempDir Path dir) throws IOException {
        Path project = sampleProject(dir, "eighty");
        Path jar = sampleJar(dir);
        AtomicInteger asked = new AtomicInteger();
        StringBuilder out = new StringBuilder();

        int exit = doctor(project, out, () -> {
            asked.incrementAndGet();
            return DoctorFacts.Extensions.found(1, List.of(jar));
        });

        assertEquals(0, exit, out.toString());
        assertTrue(out.toString().contains("Key descriptions  1 file, 1 key described"), out.toString());
        assertTrue(out.toString().contains("Config values     vidocq.sample.port=eighty — is not an int"),
                out.toString());
        assertTrue(out.toString().contains("sample.jar!/META-INF/vidocq/config-user-keys.properties:2"),
                out.toString());
        assertEquals(1, asked.get(), "the extensions are resolved once, for the count and for the class path");
    }

    @Test
    void theValuesOfTheDescribedKeysThatFitAreCounted(@TempDir Path dir) throws IOException {
        Path project = sampleProject(dir, "80");
        Path jar = sampleJar(dir);
        StringBuilder out = new StringBuilder();

        assertEquals(0, doctor(project, out, () -> DoctorFacts.Extensions.found(1, List.of(jar))), out.toString());

        assertTrue(out.toString().contains("Config values     1 described key, every value fits"), out.toString());
    }

    /** Maven failed, offline for one: no jar was read, and the two checks must not pass as if none described a key. */
    @Test
    void aProjectWhoseExtensionsCouldNotBeResolvedSaysItsJarsWereNotRead(@TempDir Path dir) throws IOException {
        Path project = sampleProject(dir, "eighty");
        StringBuilder out = new StringBuilder();

        doctor(project, out, () -> DoctorFacts.Extensions.failed("Maven exited with code 1"));

        assertTrue(out.toString().contains("Key descriptions  the jars of the project could not be read"),
                out.toString());
        assertTrue(out.toString().contains("Config values     the values were not checked against the jars"),
                out.toString());
        assertFalse(out.toString().contains("no jar of the project describes its configuration keys"), out.toString());
        assertFalse(out.toString().contains("no described key in vidocq.properties"), out.toString());
        assertFalse(out.toString().contains("is not an int"), out.toString());
    }

    /** A dependency whose jar is damaged: the line of the Maven goal, from what a resolution keeps for the doctor. */
    @Test
    void aJarThatCannotBeOpenedIsReportedAsTheMavenGoalReportsIt(@TempDir Path dir) throws IOException {
        Path project = sampleProject(dir, "80");
        Path jar = sampleJar(dir);
        Path bad = Files.writeString(dir.resolve("bad-1.0.jar"), "PK truncated");
        List<Path> described = ProjectExtensions.described(List.of(
                new ResolvedArtifact("com.acme", "sample", "1.0", jar),
                new ResolvedArtifact("com.acme", "bad", "1.0", bad)));
        StringBuilder out = new StringBuilder();

        assertEquals(0, doctor(project, out, () -> DoctorFacts.Extensions.found(1, described)), out.toString());

        assertTrue(out.toString().contains("Key descriptions  " + bad + " — cannot be read as a jar"),
                out.toString());
        assertTrue(out.toString().contains("damaged"), out.toString());
        assertTrue(out.toString().contains("Config values     1 described key, every value fits"), out.toString());
    }

    @Test
    void aDirectoryThatIsNoVidocqProjectResolvesNothing(@TempDir Path dir) {
        AtomicInteger asked = new AtomicInteger();
        StringBuilder out = new StringBuilder();

        doctor(dir, out, () -> {
            asked.incrementAndGet();
            return DoctorFacts.Extensions.found(1);
        });

        assertEquals(0, asked.get());
        assertTrue(out.toString().contains("Key descriptions  no jar of the project describes its configuration keys"),
                out.toString());
        assertTrue(out.toString().contains("Config values     no described key in vidocq.properties"), out.toString());
    }

    private static String captured(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
