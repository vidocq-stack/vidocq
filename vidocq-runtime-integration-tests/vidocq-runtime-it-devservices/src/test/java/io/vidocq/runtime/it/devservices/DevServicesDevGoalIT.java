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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real {@code mvn vidocq:dev} (spec §9), run in this module's own directory as a subprocess: dev services are
 * on by default there, the dev services extension reaches the child's boot module path in layer mode, and the
 * dev console's {@code /api/snapshot} carries the {@code devservices} panel for the host {@code vidocq:dev}. The
 * injected PostgreSQL password appears nowhere in that snapshot. Stopping {@code mvn} (as Ctrl+C does) stops the
 * child and the containers, and rewrites the state file as {@code "stopped"}.
 *
 * <p>{@code @Order(3)} ({@code junit-platform.properties} makes it effective): its subprocess overwrites the shared
 * {@code target/vidocq-dev-services.json}, after {@link DevServicesTestHostIT} and {@link DevServicesRunGoalIT}
 * are done with it.
 */
@Order(3)
class DevServicesDevGoalIT {

    /** Never 18090-18092 (the langchain4j-cdi MCP IT's own range) and never 8080/8888. */
    private static final int FIRST_PORT = 18093;
    private static final int LAST_PORT = 18099;
    private static final long BOOT_TIMEOUT_MS = 180_000;
    private static final String STARTED = "Vidocq - Started in";

    @Test
    void theDevConsoleShowsTheDevservicesPanelAndStoppingMarksTheStateFileStopped() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path logsDir = basedir.resolve("target/it-logs");
        Files.createDirectories(logsDir);
        Path log = logsDir.resolve("vidocq-dev.log");
        Path stateFile = basedir.resolve("target/vidocq-dev-services.json");

        int appPort = freePort(Set.of());
        int consolePort = freePort(Set.of(appPort));
        // Distinctive, so that a match in the snapshot is a real leak (see DevServicesRunGoalIT).
        String password = "devservices-dev-it-9Rw3Lm8v";
        List<String> command = List.of("mvn", "-ntp", "-o", "vidocq:dev",
                "-Dvidocq.dev.postgres.password=" + password,
                "-Dvidocq.chappe.listener.default.port=" + appPort,
                "-Dvidocq.devconsole.port=" + consolePort);
        Process process = new ProcessBuilder(command)
                .directory(basedir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start();
        try {
            awaitStarted(process, log);
            String body = get("http://127.0.0.1:" + consolePort + "/api/snapshot");

            assertFalse(body.contains(password), "the password leaked into the dev console snapshot");
            Map<?, ?> panel = devservicesPanel(body);
            String summary = String.valueOf(panel.get("summary"));
            assertTrue(summary.contains("postgres (postgres:16-alpine at"), "the postgres summary: " + summary);
            assertTrue(summary.contains("vidocq:dev"), "the host vidocq:dev in the summary: " + summary);
        } finally {
            stop(process);
        }

        String state = Files.readString(stateFile, StandardCharsets.UTF_8);
        assertTrue(state.contains("\"host\":\"vidocq:dev\""), state);
        assertTrue(state.contains("\"state\":\"stopped\""), "the state file after stopping: " + state);
        assertFalse(state.contains(password), "the password leaked into the state file");
    }

    /** The {@code devservices} entry of the snapshot's {@code panels}. */
    private static Map<?, ?> devservicesPanel(String body) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Map<?, ?> snapshot = (Map<?, ?>) jsonb.fromJson(body, Object.class);
            List<?> panels = (List<?>) snapshot.get("panels");
            assertNotNull(panels, "no panels in the snapshot: " + body);
            for (Object each : panels) {
                Map<?, ?> panel = (Map<?, ?>) each;
                if ("devservices".equals(panel.get("id"))) {
                    return panel;
                }
            }
            return fail("no devservices panel in the snapshot: " + body);
        }
    }

    /** Polls the log for the boot's last line; fails fast if the subprocess dies first. */
    private static void awaitStarted(Process process, Path log) throws Exception {
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log) && Files.readString(log, StandardCharsets.UTF_8).contains(STARTED)) {
                return;
            }
            if (!process.isAlive()) {
                fail("mvn vidocq:dev exited with " + process.exitValue() + " before the application started:\n"
                        + (Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "(no log)"));
            }
            Thread.sleep(200);
        }
        fail("the application did not start within " + BOOT_TIMEOUT_MS + " ms:\n"
                + Files.readString(log, StandardCharsets.UTF_8));
    }

    /** The body of a {@code GET} that must answer 200. */
    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(10_000);
        try {
            assertEquals(200, connection.getResponseCode(), url);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * SIGTERM to {@code mvn}, as Ctrl+C does: its shutdown hook stops the child and the containers. The child is
     * looked up first, so that a forced kill of {@code mvn} can never leave it running.
     */
    private static void stop(Process process) throws Exception {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
    }

    /** The first free port of {@value #FIRST_PORT}-{@value #LAST_PORT} not in {@code taken}. */
    private static int freePort(Set<Integer> taken) throws IOException {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            if (taken.contains(port)) {
                continue;
            }
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(false);
                probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (IOException inUse) {
                // try the next one
            }
        }
        throw new IOException("no free port in " + FIRST_PORT + "-" + LAST_PORT);
    }
}
