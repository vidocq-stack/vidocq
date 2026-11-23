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
package io.vidocq.ibarra.tck;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfficialTckRunnerTest {
    private static final String FULL_SUITE = "-Dtest=ee.jakarta.tck.pages.**.*IT";
    private static final String INVOCATION_END = "--invocation-end--";

    @TempDir Path work;

    @Test
    void allSelectionRunsWithoutAdditionalArguments() throws Exception {
        String output = invokeRunner(List.of("all"));

        assertTrue(output.contains("--invocation-end--"));
        assertTrue(Files.readString(work.resolve("maven-args.log")).contains(FULL_SUITE));
    }

    @Test
    void forwardsAdditionalMavenArguments() throws Exception {
        String output = invokeRunner(List.of("all", "-Dtest=ee.jakarta.tck.pages.ExtraIT"));

        assertTrue(output.contains("--invocation-end--"));
        assertTrue(Files.readString(work.resolve("maven-args.log")).contains("-Dtest=ee.jakarta.tck.pages.ExtraIT"));
    }

    // BUG-20261123-57: an install into ~/.m2 shadows the published snapshot for every other build of the machine.
    @Test
    void everyMavenCallUsesAPrivateLocalRepositoryAboveTheSharedOne() throws Exception {
        List<String> log = runRunner(List.of("--no-prepare", "all"), Map.of());

        assertEquals(3, occurrences(log, INVOCATION_END), String.join("\n", log));
        assertEquals(3, occurrences(log, "<-Dmaven.repo.local=" + work.resolve("runner/.tck-repository") + ">"),
                String.join("\n", log));
        assertEquals(3, occurrences(log, "<-Dmaven.repo.local.tail=" + System.getenv("HOME") + "/.m2/repository>"),
                String.join("\n", log));
    }

    @Test
    void thePreparationAndTheBuildsShareTheRepositoryTheEnvironmentNames() throws Exception {
        Path elsewhere = work.resolve("elsewhere");
        Path bin = Files.createDirectories(work.resolve("bin"));
        Path python = bin.resolve("python3");
        Files.writeString(python, "#!/bin/sh\nprintf 'prepare:%s\\n' \"$TCK_LOCAL_REPOSITORY\" >> \"$MAVEN_ARGS_LOG\"\n",
                StandardCharsets.UTF_8);
        assertTrue(python.toFile().setExecutable(true));

        List<String> log = runRunner(List.of("--no-install", "all"), Map.of("TCK_LOCAL_REPOSITORY", elsewhere.toString(),
                "PATH", bin + File.pathSeparator + System.getenv("PATH")));

        assertEquals("prepare:" + elsewhere, log.getFirst());
        assertEquals(2, occurrences(log, "<-Dmaven.repo.local=" + elsewhere + ">"), String.join("\n", log));
    }

    private String invokeRunner(List<String> arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add("--no-prepare");
        command.add("--no-install");
        command.addAll(arguments);
        List<String> log = runRunner(command, Map.of());

        assertEquals(2, occurrences(log, INVOCATION_END));
        return String.join("\n", log);
    }

    private List<String> runRunner(List<String> arguments, Map<String, String> environment) throws Exception {
        Path runnerDirectory = Files.createDirectories(work.resolve("runner"));
        Path runner = runnerDirectory.resolve("run-official-tck-pages-4.0.sh");
        if (!Files.exists(runner)) {
            Files.copy(projectRoot().resolve("run-official-tck-pages-4.0.sh"), runner);
        }
        Path argumentLog = work.resolve("maven-args.log");
        Path mavenWrapper = runnerDirectory.resolve("mvnw");
        Files.writeString(mavenWrapper,
                "#!/bin/sh\nprintf '<%s>\\n' \"$@\" >> \"$MAVEN_ARGS_LOG\"\nprintf '%s\\n' '--invocation-end--' >> \"$MAVEN_ARGS_LOG\"\n",
                StandardCharsets.UTF_8);
        assertTrue(mavenWrapper.toFile().setExecutable(true));

        var command = new ArrayList<String>();
        command.add("/bin/bash");
        command.add(runner.toString());
        command.addAll(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(runnerDirectory.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().remove("TCK_LOCAL_REPOSITORY");
        processBuilder.environment().put("MAVEN_ARGS_LOG", argumentLog.toString());
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        return Files.readAllLines(argumentLog);
    }

    private static long occurrences(List<String> log, String line) {
        return log.stream().filter(line::equals).count();
    }

    private Path projectRoot() throws IOException {
        String multiModuleDirectory = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModuleDirectory != null) {
            Path candidate = Path.of(multiModuleDirectory).resolve("run-official-tck-pages-4.0.sh");
            if (Files.isRegularFile(candidate)) return candidate.getParent();
        }
        Path userDirectory = Path.of(System.getProperty("user.dir"));
        for (Path candidate : List.of(userDirectory.resolve("run-official-tck-pages-4.0.sh"),
                userDirectory.resolve("../run-official-tck-pages-4.0.sh").normalize())) {
            if (Files.isRegularFile(candidate)) return candidate.getParent();
        }
        throw new IOException("Cannot locate run-official-tck-pages-4.0.sh from " + userDirectory);
    }
}
