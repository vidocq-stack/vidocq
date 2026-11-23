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
package io.vidocq.kerckhoffs.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The official TCK run script, driven from a copy next to a fake {@code mvnw} that only logs its arguments (and writes
 * the class path file it is asked for), on a stand-in of the TCK distribution whose pinned SHA-256 a fake
 * {@code shasum} reports: every Maven call it makes (the install, the class path of the container, the TCK's own
 * build) reads and writes a private local repository, with the shared {@code ~/.m2/repository} as a read-only tail
 * (BUG-20261123-57: an install into {@code ~/.m2} shadows the published snapshot for every other build of the
 * machine). Nothing is downloaded, built or run.
 */
class OfficialTckScriptTest {
    private static final String SCRIPT = "run-official-tck-security-4.0.sh";
    private static final String DISTRIBUTION = "kerckhoffs-tck/target/security-tck/jakarta-security-tck-4.0.0.zip";
    private static final String INVOCATION_END = "--invocation-end--";
    private static final String SHARED_REPOSITORY = "<-Dmaven.repo.local.tail=" + System.getenv("HOME") + "/.m2/repository>";
    private static final String FAKE_MAVEN = """
            #!/bin/sh
            for arg in "$@"; do
                case "$arg" in
                    -Dmdep.outputFile=*) printf '%s' /nowhere/container.jar > "${arg#-Dmdep.outputFile=}" ;;
                esac
            done
            printf '<%s>\\n' "$@" >> "$MAVEN_ARGS_LOG"
            printf '%s\\n' '--invocation-end--' >> "$MAVEN_ARGS_LOG"
            """;

    @TempDir
    Path work;

    @Test
    void everyMavenCallUsesARepositoryPrivateToTheCheckout() throws Exception {
        List<String> log = run(Map.of());

        // The install, the class path of the container, then the TCK's own build.
        assertEquals(3, occurrences(log, INVOCATION_END), String.join("\n", log));
        assertEquals(3, occurrences(log, "<-Dmaven.repo.local=" + work.resolve("checkout/.tck-repository") + ">"),
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
        distribution(checkout.resolve(DISTRIBUTION));
        Path bin = Files.createDirectories(work.resolve("bin"));
        writeExecutable(checkout.resolve("mvnw"), FAKE_MAVEN);
        writeExecutable(bin.resolve("shasum"), """
                #!/bin/sh
                printf '%%s  %%s\\n' '%s' "$3"
                """.formatted(pinnedSha256(script)));
        Path argumentLog = work.resolve("maven-args.log");

        ProcessBuilder builder = new ProcessBuilder("/bin/bash", script.toString()).directory(work.toFile())
                .redirectErrorStream(true);
        builder.environment().remove("TCK_LOCAL_REPOSITORY");
        builder.environment().remove("MVN_EXTRA");
        builder.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
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

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        assertTrue(file.toFile().setExecutable(true));
    }

    /** A stand-in for the official zip: the {@code tck/} directory the script looks for. */
    private static void distribution(Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("jakarta-security-tck-4.0.0/tck/pom.xml"));
            out.write("<project/>".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /** The SHA-256 the script pins for the distribution, which the fake {@code shasum} reports. */
    private static String pinnedSha256(Path script) throws IOException {
        Matcher matcher = Pattern.compile("zip_sha256='([0-9a-f]{64})'").matcher(Files.readString(script));
        assertTrue(matcher.find(), "no pinned SHA-256 in " + script);
        return matcher.group(1);
    }

    private static long occurrences(List<String> log, String line) {
        return log.stream().filter(line::equals).count();
    }

    /** The Kerckhoffs folder, found upwards from the working directory of the tests. */
    private static Path projectRoot() throws IOException {
        Path userDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = userDirectory; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(SCRIPT))) {
                return candidate;
            }
        }
        throw new IOException("Cannot locate " + SCRIPT + " from " + userDirectory);
    }
}
