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
package io.vidocq.bodoni.tck;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The official TCK run script, driven against a fake checkout whose {@code mvnw} only logs its
 * arguments: what it runs, in which order, with which selection, and what it refuses to run on
 * (a stale or missing snapshot, a missing DDL, a distribution with another checksum). Nothing is
 * downloaded and no Maven build runs.
 */
class OfficialTckRunnerTest {
    private static final String SCRIPT = "run-official-tck-tags-3.0.sh";
    private static final String NO_PREPARE = "--no-prepare";
    private static final String NO_INSTALL = "--no-install";
    private static final String VERSION = "0.4.0-SNAPSHOT";
    private static final String INVOCATION_END = "--invocation-end--";
    private static final String STALE_REPORTS = "stale-reports";
    private static final String DDL = "bodoni-tck/target/tags-tck-distribution/tags-tck/sql/derby/derby.ddl.jstl.sql";
    private static final String PACKAGE = "com.sun.ts.tests.jstl.";
    private static final String CLIENT = ".JSTLClientIT";
    private static final String SMOKE = "-Dtest=" + String.join(",",
            PACKAGE + "spec.core.general.outtag" + CLIENT,
            PACKAGE + "spec.etu.functions" + CLIENT,
            PACKAGE + "spec.etu.uri" + CLIENT,
            PACKAGE + "spec.fmt.i18n.bundle" + CLIENT,
            PACKAGE + "spec.xml.xmlcore.xout" + CLIENT,
            PACKAGE + "spec.sql.result" + CLIENT);
    private static final List<String> RUNNER_TESTS = List.of("-ntp", "-Ptck", "-pl", "bodoni-tck", "test");
    private static final List<String> OFFICIAL_SUITE =
            List.of("-ntp", "-Ptck,tck-official", "-pl", "bodoni-tck", "test");
    private static final FileTime AN_HOUR_AGO = FileTime.from(Instant.now().minus(Duration.ofHours(1)));

    @TempDir
    Path work;

    private Path checkout;
    private Path repository;
    private Path argumentLog;

    @BeforeEach
    void createAFakeCheckout() throws IOException {
        checkout = Files.createDirectories(work.resolve("checkout"));
        repository = work.resolve("repository");
        argumentLog = work.resolve("maven-args.log");
        Files.copy(projectRoot().resolve(SCRIPT), checkout.resolve(SCRIPT));
        Path mavenWrapper = write("mvnw", """
                #!/bin/sh
                printf '<%s>\\n' "$@" >> "$MAVEN_ARGS_LOG"
                printf '%s\\n' '--invocation-end--' >> "$MAVEN_ARGS_LOG"
                if [ -e bodoni-tck/target/surefire-reports/old.xml ] || [ -e bodoni-tck/target/pages-deployments ]; then
                    printf '%s\\n' 'stale-reports' >> "$MAVEN_ARGS_LOG"
                fi
                """);
        assertTrue(mavenWrapper.toFile().setExecutable(true));
        // A decoy version first: the script must read the one that follows bodoni-parent.
        aged(write("pom.xml", """
                <project>
                    <parent><artifactId>vidocq-parent</artifactId><version>9.9.9</version></parent>
                    <artifactId>bodoni-parent</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(VERSION)));
        write("bodoni-tck/pom.xml", "");
        aged(write("bodoni-core/src/main/A.java", "class A {}"));
        aged(write("bodoni-core/pom.xml", "<project/>"));
        aged(write("bodoni-peano/src/main/B.java", "class B {}"));
        aged(write("bodoni-peano/pom.xml", "<project/>"));
        aged(checkout.resolve("bodoni-core/src/main"));
        aged(checkout.resolve("bodoni-peano/src/main"));
        for (String module : List.of("bodoni-core", "bodoni-peano")) {
            Path jar = snapshotJar(module);
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "jar");
        }
        write(DDL, "");
    }

    @Test
    void installsTheReactorFirstByDefault() throws Exception {
        Run run = run(NO_PREPARE);

        assertEquals(0, run.exitCode(), run.output());
        assertEquals(3, run.invocations().size(), run.output());
        assertEquals(List.of("-ntp", "clean", "install"), run.invocations().getFirst());
    }

    @Test
    void runsTheRunnerTestsThenTheOfficialSuite() throws Exception {
        Run run = run(NO_PREPARE, NO_INSTALL);

        assertEquals(0, run.exitCode(), run.output());
        assertEquals(2, run.invocations().size(), run.output());
        assertEquals(RUNNER_TESTS, run.invocations().get(0));
        assertEquals(OFFICIAL_SUITE, run.invocations().get(1).subList(0, OFFICIAL_SUITE.size()));
    }

    @Test
    void smokeIsTheDefaultSelection() throws Exception {
        Run run = run(NO_PREPARE, NO_INSTALL);

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.invocations().getLast().contains(SMOKE), run.invocations().getLast().toString());
    }

    @Test
    void smokeCanBeNamed() throws Exception {
        Run run = run("smoke", NO_PREPARE, NO_INSTALL);

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.invocations().getLast().contains(SMOKE), run.invocations().getLast().toString());
    }

    @Test
    void allSelectsEveryOfficialClass() throws Exception {
        Run run = run("all", NO_PREPARE, NO_INSTALL);

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.invocations().getLast().contains("-Dtest=com.sun.ts.tests.jstl.**.*IT"),
                run.invocations().getLast().toString());
        assertFalse(run.invocations().getLast().contains(SMOKE));
    }

    @Test
    void forwardsATestSelectorAndExtraMavenArguments() throws Exception {
        String selector = "-Dtest=" + PACKAGE + "spec.sql.query" + CLIENT;
        Run run = run(selector, "-X", NO_PREPARE, NO_INSTALL);

        assertEquals(0, run.exitCode(), run.output());
        List<String> last = run.invocations().getLast();
        assertTrue(last.contains(selector), last.toString());
        assertTrue(last.contains("-X"), last.toString());
        assertFalse(last.contains(SMOKE), last.toString());
        assertFalse(run.invocations().getFirst().contains("-X"), "extra arguments reach the last call only");
    }

    @Test
    void clearsPreviousReportsBeforeMaven() throws Exception {
        write("bodoni-tck/target/surefire-reports/old.xml", "<testsuite/>");
        write("bodoni-tck/target/pages-deployments/old/page.jsp", "");

        Run run = run(NO_PREPARE, NO_INSTALL);

        assertEquals(0, run.exitCode(), run.output());
        assertFalse(run.log().contains(STALE_REPORTS), run.log());
        assertTrue(Files.isRegularFile(checkout.resolve(DDL)), "the prepared distribution survives");
    }

    @Test
    void noInstallRefusesAStaleSnapshot() throws Exception {
        Files.setLastModifiedTime(checkout.resolve("bodoni-core/src/main/A.java"),
                FileTime.from(Instant.now().plus(Duration.ofMinutes(1))));

        Run run = run(NO_PREPARE, NO_INSTALL);

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("run without --no-install"), run.output());
        assertTrue(run.output().contains("bodoni-core"), run.output());
        assertTrue(run.invocations().isEmpty(), run.log());
    }

    @Test
    void noInstallRefusesAMissingSnapshot() throws Exception {
        Files.delete(snapshotJar("bodoni-peano"));

        Run run = run(NO_PREPARE, NO_INSTALL);

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("bodoni-peano"), run.output());
        assertTrue(run.output().contains("run without --no-install"), run.output());
        assertTrue(run.invocations().isEmpty(), run.log());
    }

    @Test
    void noPrepareNeedsThePreparedDdl() throws Exception {
        Files.delete(checkout.resolve(DDL));

        Run run = run(NO_PREPARE, NO_INSTALL);

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("run without --no-prepare"), run.output());
        assertTrue(run.invocations().isEmpty(), run.log());
    }

    @Test
    void prepareRefusesAZipWithAnotherChecksum() throws Exception {
        Path zip = work.resolve("other.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("tags-tck/sql/derby/derby.ddl.jstl.sql"));
            out.write("create table t (c int);\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        Run run = run(Map.of("TAGS_TCK_ZIP", zip.toString()), NO_INSTALL);

        assertEquals(1, run.exitCode(), run.output());
        assertTrue(run.output().contains("checksum mismatch"), run.output());
        assertTrue(run.invocations().isEmpty(), run.log());
        assertTrue(Files.isRegularFile(zip), "a zip the script did not download is kept");
    }

    @Test
    void prepareRefusesAMissingLocalZip() throws Exception {
        Run run = run(Map.of("TAGS_TCK_ZIP", work.resolve("absent.zip").toString()), NO_INSTALL);

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("TAGS_TCK_ZIP"), run.output());
        assertTrue(run.invocations().isEmpty(), run.log());
    }

    @Test
    void refusesAnUnknownMode() throws Exception {
        Run run = run("smok", NO_PREPARE, NO_INSTALL);

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("Usage:"), run.output());
        assertTrue(run.invocations().isEmpty(), run.log());
    }

    @Test
    void refusesToRunOutsideACheckout() throws Exception {
        Files.delete(checkout.resolve("bodoni-tck/pom.xml"));

        Run run = run(NO_PREPARE, NO_INSTALL);

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("Run this script from the Bodoni checkout root."), run.output());
        assertTrue(run.invocations().isEmpty(), run.log());
    }

    private Run run(String... arguments) throws IOException, InterruptedException {
        return run(Map.of(), arguments);
    }

    private Run run(Map<String, String> environment, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("/bin/bash", checkout.resolve(SCRIPT).toString()));
        command.addAll(List.of(arguments));
        // Started from elsewhere: the script must move to its own directory.
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.remove("TAGS_TCK_ZIP");
        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("MAVEN_ARGS_LOG", argumentLog.toString());
        env.put("BODONI_M2_REPOSITORY", repository.toString());
        env.putAll(environment);
        Process process = builder.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        String log = Files.exists(argumentLog) ? Files.readString(argumentLog) : "";
        return new Run(exitCode, output, log, invocations(log));
    }

    private static List<List<String>> invocations(String log) {
        List<List<String>> invocations = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : log.lines().toList()) {
            if (INVOCATION_END.equals(line)) {
                invocations.add(List.copyOf(current));
                current.clear();
            } else if (line.startsWith("<") && line.endsWith(">")) {
                current.add(line.substring(1, line.length() - 1));
            }
        }
        return invocations;
    }

    private Path snapshotJar(String module) {
        return repository.resolve("io/vidocq/bodoni/" + module + "/" + VERSION + "/" + module + "-" + VERSION + ".jar");
    }

    private Path write(String relative, String content) throws IOException {
        Path file = checkout.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static void aged(Path path) throws IOException {
        Files.setLastModifiedTime(path, AN_HOUR_AGO);
    }

    private static Path projectRoot() throws IOException {
        Path userDirectory = Path.of(System.getProperty("user.dir"));
        for (Path candidate : List.of(userDirectory, userDirectory.resolve("..").normalize())) {
            if (Files.isRegularFile(candidate.resolve(SCRIPT))) {
                return candidate;
            }
        }
        throw new IOException("Cannot locate " + SCRIPT + " from " + userDirectory);
    }

    private record Run(int exitCode, String output, String log, List<List<String>> invocations) {}
}
