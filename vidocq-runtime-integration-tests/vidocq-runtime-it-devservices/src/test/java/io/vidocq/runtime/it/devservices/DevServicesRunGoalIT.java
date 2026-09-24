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
package io.vidocq.runtime.it.devservices;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real {@code mvn vidocq:run -Dvidocq.dev.devServices=true} (opt-in, spec §6), run in this module's own
 * directory as a subprocess whose combined output is captured to a log file: the {@code devservices}
 * startup report section reaches that log, and the PostgreSQL password never does — neither there nor in
 * {@code target/vidocq-dev-services.json}. Only {@code target/vidocq-dev-services.properties} keeps it,
 * local to the machine (spec §4.2).
 *
 * <p><b>What "the log never has it" means here.</b> The very same log also carries the plugin's
 * pre-existing, unrelated "Connection information" block (spec §2, {@code DevServicesSession}, "as
 * today"): a deliberate, documented (DEV_SERVICES.md) plain-text printout of the coordinates, password
 * included, for a developer's own {@code psql}/DataGrip — the same content
 * {@code target/vidocq-dev-services.properties} keeps on disk. That block is not part of this design and
 * is not what spec §4.2's masking rule is about. What this test asserts instead is the guarantee spec
 * §4.2/§4.4 actually add: the {@code devservices} startup report section's own {@code vidocq.pool.password}
 * row is masked ({@code section.secret(key, true)}), so it never prints the raw value next to the key.
 *
 * <p>{@code @Order(2)} ({@code junit-platform.properties} makes it effective): this test's subprocess
 * overwrites the shared {@code target/vidocq-dev-services.json} with its own {@code vidocq:run} host's
 * run, so it must run after {@link DevServicesTestHostIT} has already read that file.
 */
@Order(2)
class DevServicesRunGoalIT {

    /** Never 18090-18092 (the langchain4j-cdi MCP IT's own range) and never 8080/8888. */
    private static final int FIRST_PORT = 18093;
    private static final int LAST_PORT = 18099;
    private static final long BOOT_TIMEOUT_MS = 120_000;

    @Test
    void theDevservicesSectionReachesTheLogAndThePasswordNeverDoes() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path logsDir = basedir.resolve("target/it-logs");
        Files.createDirectories(logsDir);
        Path log = logsDir.resolve("vidocq-run.log");

        int port = freePort();
        // A password distinctive enough that a match in the log means a real leak, never the default
        // "vidocq" — which, being also this project's own name, is a substring of ordinary Maven output
        // (artifact ids, module names, "vidocq:run" itself) and would make the leak check meaningless.
        String password = "devservices-it-7Qx4Kp2z";
        List<String> command = List.of("mvn", "-ntp", "-o", "vidocq:run",
                "-Dvidocq.dev.devServices=true",
                "-Dvidocq.dev.postgres.password=" + password,
                "-Dvidocq.chappe.listener.default.port=" + port);
        Process process = new ProcessBuilder(command)
                .directory(basedir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            awaitDevServices(process, log);
            String text = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(text.contains("devservices"), "the devservices section: " + text);
            assertTrue(text.contains("1 service: postgres (postgres:16-alpine at"),
                    "the postgres summary: " + text);

            String storedPassword = readInjectedPassword(basedir);
            assertEquals(password, storedPassword, "the .properties file keeps the password vidocq:run used");
            assertMasked(text, password);
            String jsonState = Files.readString(basedir.resolve("target/vidocq-dev-services.json"),
                    StandardCharsets.UTF_8);
            assertFalse(jsonState.contains(password), "the password leaked into the state file");
        } finally {
            stop(process);
        }
    }

    /**
     * The {@code devservices} startup report section masks a secret with {@code section.secret(key, true)}
     * (spec §4.4), which prints {@code configured} — never a value — on the row of the injected key that
     * names it, {@code postgres vidocq.pool.password  configured}. Every line naming that key must show
     * that, never the raw password: the pre-existing "Connection information" block, printed under an
     * unrelated logger and unrelated key ({@code Password : <value>}, see the class Javadoc), is
     * deliberately out of scope.
     */
    private static void assertMasked(String log, String password) {
        boolean sawTheKey = false;
        for (String line : log.split("\n")) {
            if (line.contains("vidocq.pool.password")) {
                sawTheKey = true;
                assertFalse(line.contains(password), "the devservices report leaked the password: " + line);
            }
        }
        assertTrue(sawTheKey, "no line named vidocq.pool.password: the devservices report never ran?\n" + log);
    }

    /** The password {@code vidocq:run} wrote to the {@code .properties} file — local only, spec §4.2. */
    private static String readInjectedPassword(Path basedir) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(basedir.resolve("target/vidocq-dev-services.properties"))) {
            properties.load(in);
        }
        String password = properties.getProperty("datasource.default.password");
        assertTrue(password != null && !password.isBlank(), "no password in the .properties file");
        return password;
    }

    /** Polls the log for the postgres summary; fails fast if the subprocess dies first. */
    private static void awaitDevServices(Process process, Path log) throws Exception {
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log)) {
                String text = Files.readString(log, StandardCharsets.UTF_8);
                if (text.contains("1 service: postgres (postgres:16-alpine at")) {
                    return;
                }
            }
            if (!process.isAlive()) {
                fail("mvn vidocq:run exited with " + process.exitValue() + " before starting dev services:\n"
                        + (Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "(no log)"));
            }
            Thread.sleep(200);
        }
        stop(process);
        fail("dev services did not appear in the log within " + BOOT_TIMEOUT_MS + " ms");
    }

    /** Ctrl+C, then a forced kill if it does not stop cleanly. */
    private static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** The first free port of {@value #FIRST_PORT}-{@value #LAST_PORT}; never 8080 or 8888. */
    private static int freePort() throws IOException {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(false);
                probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (IOException taken) {
                // try the next one
            }
        }
        throw new IOException("no free port in " + FIRST_PORT + "-" + LAST_PORT);
    }
}
