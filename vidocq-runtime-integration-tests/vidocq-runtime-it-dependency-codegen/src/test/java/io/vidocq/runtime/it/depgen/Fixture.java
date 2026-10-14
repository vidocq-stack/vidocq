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
package io.vidocq.runtime.it.depgen;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** The fixture project and the subprocesses of the dependency codegen ITs. */
final class Fixture {

    static final Duration TIMEOUT = Duration.ofMinutes(3);

    /** The Foy codegen bundle in the application's compiler configuration. */
    private static final Pattern CODEGEN_BUNDLE =
            Pattern.compile("\\s*<annotationProcessorPaths combine.children=\"append\">.*?</annotationProcessorPaths>",
                    Pattern.DOTALL);

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

    /** Deletes the Foy codegen bundle from the application's {@code annotationProcessorPaths}. */
    static void withoutCodegenBundle(Path project) throws IOException {
        Path pom = project.resolve("app/pom.xml");
        String before = Files.readString(pom);
        String after = CODEGEN_BUNDLE.matcher(before).replaceFirst("");
        assertNotEquals(before, after, "the fixture's app/pom.xml no longer declares the codegen bundle");
        Files.writeString(pom, after);
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
     * The {@code -Dvidocq.generate.cacheDir} of one IT: an empty directory under target/it-caches, so a build never
     * reads the user's cache nor an entry an earlier IT wrote.
     */
    static String cacheDir(String name) throws IOException {
        Path cache = Path.of(System.getProperty("it.caches")).resolve(name);
        if (Files.exists(cache)) {
            try (Stream<Path> files = Files.walk(cache)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.createDirectories(cache);
        return "-Dvidocq.generate.cacheDir=" + cache;
    }

    /** Builds the library and the application into the fixture's repository, online, once per IT. */
    static void install(Path project, Path log, String cacheDir) throws Exception {
        awaitSuccess(start(project, log, "install", "-DskipTests", cacheDir), log, "the fixture build");
    }

    /** Waits for a build to end with 0 within {@link #TIMEOUT}; fails with its log otherwise. */
    static void awaitSuccess(Process process, Path log, String what) throws Exception {
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            stop(process);
            fail(what + " did not end within " + TIMEOUT + ":\n" + read(log));
        }
        assertEquals(0, process.exitValue(), () -> what + " failed:\n" + read(log));
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
                fail("the process exited with " + process.exitValue() + " before '" + text + "':\n" + read(log));
            }
            Thread.sleep(250);
        }
        fail("no '" + text + "' within " + TIMEOUT + ":\n" + read(log));
    }

    /** The lines of {@code log} that contain every one of {@code parts}. */
    static List<String> lines(Path log, String... parts) {
        return read(log).lines().filter(line -> Stream.of(parts).allMatch(line::contains)).toList();
    }

    /** A loopback port nothing listens on now, for the application's HTTP listener. */
    static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return probe.getLocalPort();
        }
    }

    /** Stops a subprocess and everything it started. */
    static void stop(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
