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

import io.vidocq.runtime.cli.update.UpdatePlan;
import io.vidocq.runtime.tooling.PomConverter.Target;
import io.vidocq.runtime.tooling.ProjectScaffolder;
import io.vidocq.runtime.tooling.ProjectSpec;
import io.vidocq.runtime.tooling.Reporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#380: {@code vidocq pom convert} on a project: the diff is printed, the pom is written unless
 * {@code --check}, which exits with 1 when a conversion is needed; an unrecognised pom is refused and left alone.
 */
class CommandRunnerPomTest {

    private static final Reporter SILENT = new Reporter() {
        @Override public void success(String message, String detail) { /* quiet */ }
        @Override public void note(String message) { /* quiet */ }
        @Override public void warning(String message) { /* quiet */ }
        @Override public void error(String message) { /* quiet */ }
    };

    @Test
    void convertsThePomInPlaceAndPrintsTheDiff(@TempDir Path dir) throws IOException {
        Path project = withParent(dir);
        StringBuilder out = new StringBuilder();

        int status = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENTLESS, false),
                project));

        assertEquals(0, status, out.toString());
        String pom = Files.readString(project.resolve("pom.xml"));
        assertFalse(pom.contains("<parent>"), pom);
        assertTrue(pom.contains("<extensions>true</extensions>"), pom);
        assertTrue(out.toString().contains("-        <artifactId>vidocq-runtime-parent</artifactId>"), out.toString());
        assertTrue(out.toString().contains("+                <extensions>true</extensions>"), out.toString());
    }

    @Test
    void checkReportsWithoutWriting(@TempDir Path dir) throws IOException {
        Path project = withParent(dir);
        String before = Files.readString(project.resolve("pom.xml"));
        StringBuilder out = new StringBuilder();

        int needed = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENTLESS, true),
                project));
        int already = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENT, true),
                project));

        assertEquals(1, needed, out.toString());
        assertEquals(0, already, out.toString());
        assertEquals(before, Files.readString(project.resolve("pom.xml")), "--check writes nothing");
    }

    @Test
    void anUnrecognisedPomIsRefusedAndLeftAlone(@TempDir Path dir) throws IOException {
        String plain = "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId><artifactId>b</artifactId>"
                + "<version>1</version></project>\n";
        Files.writeString(dir.resolve("pom.xml"), plain);
        StringBuilder out = new StringBuilder();

        int status = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENTLESS, false), dir));

        assertEquals(1, status);
        assertEquals(plain, Files.readString(dir.resolve("pom.xml")));
    }

    @Test
    void aDirectoryWithoutAPomIsRefused(@TempDir Path dir) {
        StringBuilder out = new StringBuilder();

        int status = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENT, false), dir));

        assertEquals(1, status);
    }

    // ---- vidocq pom upgrade (Vidocq/vidocq#510) ----

    private static final String RELEASE_METADATA =
            "<metadata><versioning><release>0.5.0</release></versioning></metadata>";
    private static final String SNAPSHOT_METADATA = "<metadata><versioning><versions>"
            + "<version>0.6.0-SNAPSHOT</version></versions></versioning></metadata>";

    /** The catalogue: 0.5.0 on Maven Central, 0.6.0-SNAPSHOT on the snapshot repository. */
    private static Optional<String> catalogue(String url) {
        return Optional.of(url.startsWith("https://maven.vidocq.dev") ? SNAPSHOT_METADATA : RELEASE_METADATA);
    }

    private static Optional<String> noCatalogue(String url) {
        throw new AssertionError("the catalogue is not read for --to: " + url);
    }

    private static int upgrade(StringBuilder out, Path project, String to, UpdatePlan.Channel channel, boolean check,
                               Function<String, Optional<String>> fetch) {
        return capture(out, () -> CommandRunner.runPomUpgrade(new Command.PomUpgrade(to, channel, check), project,
                fetch));
    }

    private static Path pomAt(Path dir, boolean withParent, String version) throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("cassini-rest"), null,
                false, false, withParent), dir, version, SILENT);
        return dir.resolve("demo");
    }

    @Test
    void upgradesBothStylesToTheLatestReleaseAndPrintsTheDiff(@TempDir Path dir) throws IOException {
        for (boolean withParent : new boolean[] {false, true}) {
            Path project = pomAt(Files.createTempDirectory(dir, "p"), withParent, "0.4.0");
            StringBuilder out = new StringBuilder();

            int status = upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, false, CommandRunnerPomTest::catalogue);

            assertEquals(0, status, out.toString());
            assertTrue(Files.readString(project.resolve("pom.xml")).contains("0.5.0"));
            assertFalse(Files.readString(project.resolve("pom.xml")).contains("0.4.0"));
            assertTrue(out.toString().contains("+") && out.toString().contains("0.5.0"), out.toString());
        }
    }

    @Test
    void checkExitsWithOneWhenAnUpgradeIsAvailableAndWritesNothing(@TempDir Path dir) throws IOException {
        Path project = pomAt(dir, false, "0.4.0");
        String before = Files.readString(project.resolve("pom.xml"));
        StringBuilder out = new StringBuilder();

        int status = upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, true, CommandRunnerPomTest::catalogue);

        assertEquals(1, status, out.toString());
        assertEquals(before, Files.readString(project.resolve("pom.xml")));
    }

    @Test
    void checkAndASecondRunExitWithZeroOnceUpToDate(@TempDir Path dir) throws IOException {
        Path project = pomAt(dir, false, "0.4.0");
        StringBuilder out = new StringBuilder();
        upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, false, CommandRunnerPomTest::catalogue);
        String once = Files.readString(project.resolve("pom.xml"));

        int again = upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, false, CommandRunnerPomTest::catalogue);
        int check = upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, true, CommandRunnerPomTest::catalogue);

        assertEquals(0, again, out.toString());
        assertEquals(0, check, out.toString());
        assertEquals(once, Files.readString(project.resolve("pom.xml")));
    }

    @Test
    void toTakesAVersionWithoutReadingTheCatalogue(@TempDir Path dir) throws IOException {
        Path project = pomAt(dir, false, "0.4.0");
        StringBuilder out = new StringBuilder();

        int status = upgrade(out, project, "0.4.1", UpdatePlan.Channel.INSTALLED, false,
                CommandRunnerPomTest::noCatalogue);

        assertEquals(0, status, out.toString());
        assertTrue(Files.readString(project.resolve("pom.xml")).contains("<vidocq.version>0.4.1</vidocq.version>"));
    }

    @Test
    void snapshotMovesToTheHighestSnapshotLine(@TempDir Path dir) throws IOException {
        Path project = pomAt(dir, false, "0.4.0");
        StringBuilder out = new StringBuilder();

        int status = upgrade(out, project, null, UpdatePlan.Channel.SNAPSHOT, false, CommandRunnerPomTest::catalogue);

        String pom = Files.readString(project.resolve("pom.xml"));
        assertEquals(0, status, out.toString());
        assertTrue(pom.contains("<vidocq.version>0.6.0-SNAPSHOT</vidocq.version>"), pom);
        assertTrue(pom.contains("vidocq-snapshots"), pom);
    }

    @Test
    void aPomBeforeZeroFourZeroIsUpgraded(@TempDir Path dir) throws IOException {
        Path project = pomAt(dir, true, "0.3.0");
        StringBuilder out = new StringBuilder();

        int status = upgrade(out, project, "0.4.0", UpdatePlan.Channel.INSTALLED, false,
                CommandRunnerPomTest::noCatalogue);

        assertEquals(0, status, out.toString());
        assertFalse(Files.readString(project.resolve("pom.xml")).contains("0.3.0"), Files.readString(project.resolve("pom.xml")));
    }

    @Test
    void versionsThatDisagreeAreReportedAndNothingIsWritten(@TempDir Path dir) throws IOException {
        Path project = pomAt(dir, false, "0.4.0");
        String pom = Files.readString(project.resolve("pom.xml")).replace(
                "<artifactId>vidocq-runtime-core</artifactId>",
                "<artifactId>vidocq-runtime-core</artifactId>\n            <version>0.3.0</version>");
        Files.writeString(project.resolve("pom.xml"), pom);
        StringBuilder out = new StringBuilder();

        int status = upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, false, CommandRunnerPomTest::catalogue);
        int check = upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, true, CommandRunnerPomTest::catalogue);

        assertEquals(1, status);
        assertEquals(1, check);
        assertTrue(out.toString().contains("disagree"), out.toString());
        assertEquals(pom, Files.readString(project.resolve("pom.xml")));
    }

    @Test
    void aCatalogueThatDoesNotAnswerIsReportedAndNothingIsWritten(@TempDir Path dir) throws IOException {
        Path project = pomAt(dir, false, "0.4.0");
        String before = Files.readString(project.resolve("pom.xml"));
        StringBuilder out = new StringBuilder();

        int status = upgrade(out, project, null, UpdatePlan.Channel.INSTALLED, false, url -> Optional.empty());

        assertEquals(1, status, out.toString());
        assertEquals(before, Files.readString(project.resolve("pom.xml")));
    }

    @Test
    void anUnrecognisedPomOrNoPomIsRefused(@TempDir Path dir) throws IOException {
        StringBuilder out = new StringBuilder();
        assertEquals(1, upgrade(out, dir, "0.5.0", UpdatePlan.Channel.INSTALLED, false, CommandRunnerPomTest::noCatalogue));

        Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId>"
                + "<artifactId>b</artifactId><version>1</version></project>\n");

        assertEquals(1, upgrade(out, dir, "0.5.0", UpdatePlan.Channel.INSTALLED, false, CommandRunnerPomTest::noCatalogue));
    }

    private static Path withParent(Path dir) throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("cassini-rest"), null,
                false, false, true), dir, "0.4.0", SILENT);
        return dir.resolve("demo");
    }

    /** Runs {@code action} with stdout and stderr captured into {@code out}. */
    private static int capture(StringBuilder out, java.util.function.IntSupplier action) {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(stream);
        System.setErr(stream);
        try {
            return action.getAsInt();
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
            out.append(buffer.toString(StandardCharsets.UTF_8));
        }
    }
}
