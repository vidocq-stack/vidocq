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
package io.vidocq.peano.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The official TCK run script, run in a fake checkout whose {@code mvnw} only logs its arguments, on a local copy of
 * the distribution: every Maven call it makes (the TCK jar install, the reactor install, the run) reads and writes a
 * private local repository, with the shared {@code ~/.m2/repository} as a read-only tail (BUG-20261123-57: an install
 * into {@code ~/.m2} shadows the published snapshot for every other build of the machine). Nothing is downloaded,
 * built or run.
 */
class OfficialTckScriptTest {
    private static final String SCRIPT = "run-official-tck-el-6.0.sh";
    private static final String INVOCATION_END = "--invocation-end--";
    private static final String SHARED_REPOSITORY = "<-Dmaven.repo.local.tail=" + System.getenv("HOME") + "/.m2/repository>";

    @TempDir
    Path work;

    @Test
    void everyMavenCallUsesARepositoryPrivateToTheCheckout() throws Exception {
        List<String> log = run(Map.of());

        assertEquals(3, occurrences(log, INVOCATION_END), String.join("\n", log));
        // The script runs where it is started, without a cd: bash reads the physical working directory.
        Path checkout = work.resolve("checkout").toRealPath();
        assertEquals(3, occurrences(log, "<-Dmaven.repo.local=" + checkout.resolve(".tck-repository") + ">"),
                String.join("\n", log));
        assertEquals(3, occurrences(log, SHARED_REPOSITORY), String.join("\n", log));
    }

    @Test
    void theEnvironmentCanMoveThePrivateRepository() throws Exception {
        Path elsewhere = work.resolve("elsewhere");

        List<String> log = run(Map.of("TCK_LOCAL_REPOSITORY", elsewhere.toString()));

        assertEquals(3, occurrences(log, "<-Dmaven.repo.local=" + elsewhere + ">"), String.join("\n", log));
    }

    private List<String> run(Map<String, String> environment) throws IOException, InterruptedException {
        Path checkout = Files.createDirectories(work.resolve("checkout"));
        Path script = Files.copy(projectRoot().resolve(SCRIPT), checkout.resolve(SCRIPT));
        Files.createDirectories(checkout.resolve("peano-tck"));
        Files.writeString(checkout.resolve("peano-tck/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path argumentLog = work.resolve("maven-args.log");
        Path mavenWrapper = checkout.resolve("mvnw");
        Files.writeString(mavenWrapper, """
                #!/bin/sh
                printf '<%s>\\n' "$@" >> "$MAVEN_ARGS_LOG"
                printf '%s\\n' '--invocation-end--' >> "$MAVEN_ARGS_LOG"
                """, StandardCharsets.UTF_8);
        assertTrue(mavenWrapper.toFile().setExecutable(true));

        ProcessBuilder builder = new ProcessBuilder("/bin/bash", script.toString(), "all").directory(checkout.toFile())
                .redirectErrorStream(true);
        builder.environment().remove("TCK_LOCAL_REPOSITORY");
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("EL_TCK_ZIP", distribution().toString());
        builder.environment().put("MAVEN_ARGS_LOG", argumentLog.toString());
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, process.waitFor(), output);
        return Files.readAllLines(argumentLog);
    }

    /** A stand-in for the official zip: the one entry the script extracts. */
    private Path distribution() throws IOException {
        Path zip = work.resolve("jakarta-expression-language-tck-6.0.1.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("el-tck/artifacts/jakarta-expression-language-tck-6.0.1.jar"));
            out.write("jar".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static long occurrences(List<String> log, String line) {
        return log.stream().filter(line::equals).count();
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
}
