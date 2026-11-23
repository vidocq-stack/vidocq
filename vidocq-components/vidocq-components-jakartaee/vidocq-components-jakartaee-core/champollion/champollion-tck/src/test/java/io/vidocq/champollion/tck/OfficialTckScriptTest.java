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
package io.vidocq.champollion.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JSON-P and JSON-B run scripts, driven from copies next to a fake {@code mvnw}, with a fake {@code mvn} first on
 * the {@code PATH}, both of which only log their arguments: every Maven call they make (the reactor install, the
 * runs) reads and writes a private local repository, with the shared {@code ~/.m2/repository} as a read-only tail
 * (BUG-20261123-57: an install into {@code ~/.m2} shadows the published snapshot for every other build of the
 * machine). {@code HOME} is a scratch folder holding placeholders of the two TCK jars the scripts look for. Nothing
 * is built or run.
 */
class OfficialTckScriptTest {
    private static final String JSONP_SCRIPT = "run-official-tck-jsonp-2.1.sh";
    private static final String JSONB_SCRIPT = "run-official-tck-jsonb-3.0.sh";
    private static final List<String> TCK_JARS = List.of(
            ".m2/repository/jakarta/json/jakarta.json-tck-tests/2.1.1/jakarta.json-tck-tests-2.1.1.jar",
            ".m2/repository/jakarta/json/bind/jakarta.json.bind-tck/3.0.0/jakarta.json.bind-tck-3.0.0.jar");
    private static final String INVOCATION_END = "--invocation-end--";
    private static final String FAKE_MAVEN = """
            #!/bin/sh
            printf '<%s>\\n' "$@" >> "$MAVEN_ARGS_LOG"
            printf '%s\\n' '--invocation-end--' >> "$MAVEN_ARGS_LOG"
            """;

    @TempDir
    Path work;

    @Test
    void theJsonpInstallAndBothRunsUseARepositoryPrivateToTheCheckout() throws Exception {
        List<String> log = run(JSONP_SCRIPT, Map.of());

        // The install, then the api and the pluggability suites.
        assertEquals(3, occurrences(log, INVOCATION_END), String.join("\n", log));
        assertEquals(3, occurrences(log, "<-Dmaven.repo.local=" + work.resolve("checkout/.tck-repository") + ">"),
                String.join("\n", log));
        assertEquals(3, occurrences(log, sharedRepository()), String.join("\n", log));
    }

    @Test
    void theJsonbInstallAndRunUseARepositoryPrivateToTheCheckout() throws Exception {
        List<String> log = run(JSONB_SCRIPT, Map.of());

        assertEquals(2, occurrences(log, INVOCATION_END), String.join("\n", log));
        assertEquals(2, occurrences(log, "<-Dmaven.repo.local=" + work.resolve("checkout/.tck-repository") + ">"),
                String.join("\n", log));
        assertEquals(2, occurrences(log, sharedRepository()), String.join("\n", log));
    }

    @Test
    void theEnvironmentCanMoveThePrivateRepository() throws Exception {
        Path elsewhere = work.resolve("elsewhere");

        List<String> log = run(JSONB_SCRIPT, Map.of("TCK_LOCAL_REPOSITORY", elsewhere.toString()));

        assertEquals(2, occurrences(log, "<-Dmaven.repo.local=" + elsewhere + ">"), String.join("\n", log));
    }

    private String sharedRepository() {
        return "<-Dmaven.repo.local.tail=" + work.resolve("home/.m2/repository") + ">";
    }

    private List<String> run(String scriptName, Map<String, String> environment)
            throws IOException, InterruptedException {
        Path checkout = Files.createDirectories(work.resolve("checkout"));
        Path script = Files.copy(projectRoot(scriptName).resolve(scriptName), checkout.resolve(scriptName));
        Path home = work.resolve("home");
        for (String tckJar : TCK_JARS) {
            Path jar = home.resolve(tckJar);
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "jar", StandardCharsets.UTF_8);
        }
        Path bin = Files.createDirectories(work.resolve("bin"));
        for (Path maven : List.of(checkout.resolve("mvnw"), bin.resolve("mvn"))) {
            Files.writeString(maven, FAKE_MAVEN, StandardCharsets.UTF_8);
            assertTrue(maven.toFile().setExecutable(true));
        }
        Path argumentLog = work.resolve("maven-args.log");

        ProcessBuilder builder = new ProcessBuilder("/bin/bash", script.toString()).directory(work.toFile())
                .redirectErrorStream(true);
        builder.environment().remove("TCK_LOCAL_REPOSITORY");
        builder.environment().put("HOME", home.toString());
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

    private static long occurrences(List<String> log, String line) {
        return log.stream().filter(line::equals).count();
    }

    /** The Champollion folder, found upwards from the working directory of the tests. */
    private static Path projectRoot(String scriptName) throws IOException {
        Path userDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = userDirectory; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(scriptName))) {
                return candidate;
            }
        }
        throw new IOException("Cannot locate " + scriptName + " from " + userDirectory);
    }
}
