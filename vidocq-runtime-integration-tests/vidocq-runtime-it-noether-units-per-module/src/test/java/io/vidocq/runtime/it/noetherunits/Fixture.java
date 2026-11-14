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
package io.vidocq.runtime.it.noetherunits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** The fixture project and the subprocesses of the persistence-units-per-module IT. */
final class Fixture {

    static final Duration TIMEOUT = Duration.ofMinutes(3);

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

    /** Renames the unit of the application's persistence.xml, so that it takes the name of another. */
    static void renameApplicationUnit(Path project, String from, String to) throws IOException {
        Path xml = project.resolve("app/src/main/resources/META-INF/persistence.xml");
        String before = Files.readString(xml);
        String after = before.replace("name=\"" + from + "\"", "name=\"" + to + "\"");
        assertNotEquals(before, after, "the fixture's persistence.xml no longer declares the unit " + from);
        Files.writeString(xml, after);
    }

    /** Adds keys to the application's {@code vidocq.properties}, which the build reads too. */
    static void addApplicationKeys(Path project, String keys) throws IOException {
        Path properties = project.resolve("app/src/main/resources/vidocq.properties");
        Files.writeString(properties, Files.readString(properties) + "\n" + keys + "\n");
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
            if (process.waitFor(250, TimeUnit.MILLISECONDS) && !read(log).contains(text)) {
                fail("the process exited with " + process.exitValue() + " before '" + text + "':\n" + read(log));
            }
        }
        fail("no '" + text + "' within " + TIMEOUT + ":\n" + read(log));
    }

    /** The lines of {@code log} that contain every one of {@code parts}. */
    static List<String> lines(Path log, String... parts) {
        return read(log).lines().filter(line -> Stream.of(parts).allMatch(line::contains)).toList();
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
