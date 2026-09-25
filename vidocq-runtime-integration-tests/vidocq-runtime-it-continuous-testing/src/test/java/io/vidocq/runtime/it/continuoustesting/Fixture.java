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
package io.vidocq.runtime.it.continuoustesting;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** The fixture project and the subprocesses of the continuous-testing ITs. */
final class Fixture {

    /** Never 8080/8888; 18093-18099, as the other dev-mode ITs. */
    static final int FIRST_PORT = 18093;
    static final int LAST_PORT = 18099;
    static final Duration TIMEOUT = Duration.ofMinutes(3);
    static final String EXPECTATION = "src/test/java/io/vidocq/it/ct/ExpectationTest.java";

    private Fixture() {}

    /** A copy of the fixture project under {@code dir}, outside this repository and its mvnw. */
    static Path copy(Path dir) throws IOException {
        writeSettings();
        Path template = Path.of(System.getProperty("it.fixture"));
        Path project = dir.resolve("fixture");
        try (Stream<Path> files = Files.walk(template)) {
            for (Path source : files.toList()) {
                Path target = project.resolve(template.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return project;
    }

    /**
     * Writes the settings the fixture's builds use ({@code .mvn/maven.config}): the outer local repository as a
     * {@code file:} repository, for everything target/it-repo lacks.
     */
    static void writeSettings() throws IOException {
        String url = Path.of(System.getProperty("it.outerRepository")).toUri().toString();
        String template = Files.readString(Path.of(System.getProperty("it.settings.template")));
        Files.writeString(Path.of(System.getProperty("it.settings")), template.replace("@localRepositoryUrl@", url));
    }

    /** Where an IT writes the logs of its subprocesses, kept after the build for a post-mortem. */
    static Path logs() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("it.logs")));
    }

    /**
     * One online build that fills target/it-repo with what the plugin's offline runs need later (Surefire's JUnit
     * provider, the compiler); the fixture's own failures are ignored.
     */
    static void warmUp(Path project, Path log) throws Exception {
        Process process = start(project, log, "test-compile", "surefire:test", "-Dmaven.test.failure.ignore=true");
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            stop(process);
            fail("the warm-up build did not end within " + TIMEOUT + ":\n" + read(log));
        }
        assertEquals(0, process.exitValue(), () -> "the warm-up build failed:\n" + read(log));
    }

    static Process start(Path project, Path log, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("mvn", "-ntp", "-B"));
        command.addAll(List.of(args));
        return new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "(no file)";
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    /** Polls {@code log} for {@code text}; fails fast if the subprocess dies first. */
    static void awaitLog(Process process, Path log, String text) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (read(log).contains(text)) {
                return;
            }
            if (!process.isAlive()) {
                fail("mvn exited with " + process.exitValue() + " before '" + text + "':\n" + read(log));
            }
            Thread.sleep(250);
        }
        fail("no '" + text + "' within " + TIMEOUT + ":\n" + read(log));
    }

    /** Polls the results file until {@code done} accepts it. */
    static Map<String, Object> awaitResults(Path project, Predicate<Map<String, Object>> done) throws Exception {
        Path file = project.resolve("target/vidocq-dev-tests.json");
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) {
                last = json(read(file));
                if (done.test(last)) {
                    return last;
                }
            }
            Thread.sleep(250);
        }
        return fail("no matching results within " + TIMEOUT + "; the last: " + last);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String text) {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return jsonb.fromJson(text, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + text, e);
        }
    }

    static boolean is(Map<String, Object> results, String state, String trigger) {
        return state.equals(results.get("state")) && trigger.equals(results.get("trigger"));
    }

    static int count(Map<String, Object> results, String key) {
        return ((Number) ((Map<?, ?>) results.get("counts")).get(key)).intValue();
    }

    /** Rewrites the failing expectation so that the test passes. */
    static void fixExpectation(Path project) throws IOException {
        Path test = project.resolve(EXPECTATION);
        Files.writeString(test, Files.readString(test).replace("\"Goodbye, Ada\"", "\"Hello, Ada\""));
    }

    static int occurrences(Path log, String text) {
        String all = read(log);
        int count = 0;
        for (int i = all.indexOf(text); i >= 0; i = all.indexOf(text, i + text.length())) {
            count++;
        }
        return count;
    }

    /** The panel {@code id} of a snapshot. */
    static Map<?, ?> panel(Map<String, Object> snapshot, String id) {
        for (Object each : (List<?>) snapshot.get("panels")) {
            if (id.equals(((Map<?, ?>) each).get("id"))) {
                return (Map<?, ?>) each;
            }
        }
        return fail("no panel " + id + " in " + snapshot);
    }

    /** The value {@code key} of a panel's sample, or {@code null}. */
    static Map<?, ?> value(Map<?, ?> panel, String key) {
        Map<?, ?> sample = (Map<?, ?>) panel.get("sample");
        if (sample == null) {
            return null;
        }
        for (Object each : (List<?>) sample.get("values")) {
            if (key.equals(((Map<?, ?>) each).get("key"))) {
                return (Map<?, ?>) each;
            }
        }
        return null;
    }

    static String get(String url) throws IOException {
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

    /** An action, sent as the console's own page sends it (ADR 0001); its answer must be 200. */
    static String postAction(String console, String action, String token, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(console + "/api/action/" + action).toURL()
                .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Origin", console);
        connection.setRequestProperty("X-Vidocq-Console-Token", token);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, connection.getResponseCode(), action);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * SIGTERM to {@code mvn}, as Ctrl+C does: its shutdown hook stops the test run and the child. The descendants
     * are looked up first, so that a forced kill of {@code mvn} can never leave one running.
     */
    static void stop(Process process) throws Exception {
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
    static int freePort(Set<Integer> taken) throws IOException {
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
