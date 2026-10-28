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
package io.vidocq.runtime.it.create;

import io.vidocq.runtime.tooling.ProjectScaffolder;
import io.vidocq.runtime.tooling.ProjectSpec;
import io.vidocq.runtime.tooling.Reporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Vidocq/vidocq#339 (evolution 6): the project {@code vidocq create -x cassini-rest} writes passes
 * {@code mvn verify vidocq:jlink} on this build: it compiles on {@code vidocq-runtime-parent}, whose plugin wires the
 * codegen bundles, its first test starts the application with {@code @VidocqTest} and calls {@code GET /hello},
 * {@code vidocq:package} writes the distribution, and {@code vidocq:jlink} the self-contained image
 * ({@code vidocq build jlink}).
 */
class CreateThenBuildIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** The javac warnings the created project is allowed: one per class Vauban writes last (#337, documented). */
    private static final String LAST_ROUND = "created in the last round will not be subject to annotation processing";

    /** The IT checks what the scaffolder writes and what the build prints, not what the scaffolder says. */
    private static final Reporter SILENT = new Reporter() {
        @Override public void success(String message, String detail) { /* see SILENT */ }
        @Override public void note(String message) { /* see SILENT */ }
        @Override public void warning(String message) { /* see SILENT */ }
        @Override public void error(String message) { /* see SILENT */ }
    };

    @Test
    void aCreatedRestApplicationBuildsTestsItselfPackagesAndLinksWithoutAWarning(@TempDir Path dir) throws Exception {
        String version = System.getProperty("it.version");
        ProjectScaffolder.scaffold(new ProjectSpec("demo-app", "com.acme", "com.acme.demo.app",
                Set.of("cassini-rest"), null, false), dir, version, SILENT);
        Path project = dir.resolve("demo-app");
        useThisBuildsArtifacts(project);

        Path log = Files.createDirectories(Path.of(System.getProperty("it.logs"))).resolve("create-then-build.log");
        Process build = new ProcessBuilder("mvn", "-ntp", "-B", "verify", "vidocq:jlink")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!build.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            build.destroyForcibly();
            fail("mvn verify vidocq:jlink did not end within " + TIMEOUT + ":\n" + read(log));
        }
        String output = read(log);
        assertEquals(0, build.exitValue(), () -> "mvn verify vidocq:jlink failed:\n" + output);
        assertFalse(Files.readString(project.resolve("pom.xml")).contains("annotationProcessorPaths"),
                "the parent's plugin wires the codegen bundles: the created pom lists none");

        assertTrue(output.contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed")
                && output.contains("in com.acme.demo.app.HelloResourceTest"), "the first test did not run:\n" + output);
        assertTrue(Files.isRegularFile(project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh")),
                "vidocq:package wrote no launcher:\n" + output);
        assertTrue(Files.isRegularFile(project.resolve("target/dist/bin/demo-app")),
                "vidocq:jlink wrote no image launcher:\n" + output);
        assertTrue(modules(project.resolve("target/dist/bin/java")).contains("com.acme.demo.app"),
                "the image does not link the application module:\n" + output);
        List<String> warnings = output.lines()
                .filter(l -> l.startsWith("[WARNING]") && !l.contains(LAST_ROUND))
                .toList();
        assertEquals(List.of(), warnings, "the created project builds with warnings:\n" + output);
        assertEquals(2, output.lines().filter(l -> l.contains(LAST_ROUND)).count(), output);
    }

    /**
     * The created project's builds resolve this build's artifacts from target/it-repo and everything else from the
     * outer local repository, through {@code .mvn/maven.config}: they never reach the network.
     */
    private static void useThisBuildsArtifacts(Path project) throws IOException {
        Path settings = Path.of(System.getProperty("it.settings"));
        String url = Path.of(System.getProperty("it.outerRepository")).toUri().toString();
        String template = Files.readString(Path.of(System.getProperty("it.settings.template")));
        Files.writeString(settings, template.replace("@localRepositoryUrl@", url));
        Files.writeString(project.resolve(".mvn/maven.config"),
                "-Dmaven.repo.local=" + System.getProperty("it.repo") + "\n--settings\n" + settings + "\n");
    }

    /** What {@code java --list-modules} of an image prints. */
    private static String modules(Path java) throws IOException, InterruptedException {
        Process list = new ProcessBuilder(java.toString(), "--list-modules").redirectErrorStream(true).start();
        String out = new String(list.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        list.waitFor(1, TimeUnit.MINUTES);
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "(no file)";
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
