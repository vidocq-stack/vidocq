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
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-09: under {@code vidocq:dev} the application reads its classes from the output of the build, and the
 * compiler plugin removes from that output the classes it created before it compiles the module again. While the
 * sources do not compile, those classes stay out: an application that had not loaded one of them yet no longer could,
 * and the page that needed it answered 500, for good once the JVM had recorded the lookup that failed.
 *
 * <p>The application starts and serves nothing. A source is saved with an error: the resource, whose classes no
 * request had asked for yet, must answer. The source is then saved with one more method, which swaps the layer, and
 * with an error again before any request reaches the new layer: the resource must answer there too. A second
 * application, started with the classes left to their first use, is the control: its resource fails.
 */
class AppLayerBrokenSourcesIT {

    /** The line a boot logs once the application serves. */
    private static final String STARTED = "Vidocq - Started in";

    private static final String COMPILE_FAILED = "Compile failed (exit 1); keeping previous JVM up.";

    /** The resource of the fixture: its client proxy is loaded by the first request. */
    private static final String GREETING = "/api/greeting?name=Ada";

    private static final String GREETING_SOURCE = "src/main/java/acme/reload/Greeting.java";

    @Test
    void theApplicationServesWhileItsSourcesDoNotCompile(@TempDir Path dir) throws Exception {
        Path project = built(dir, "broken-sources");
        Path log = Fixture.logs().resolve("broken-sources-dev.log");

        // No debug agent and a free port for the dev console, as AppLayerReloadLeakIT.
        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.devconsole.port=0");
        try (HttpClient client = HttpClient.newHttpClient()) {
            Fixture.awaitOccurrences(mvn, log, STARTED, 1);

            // The first layer. No request so far: what the first one needs is not in use yet.
            String source = Files.readString(project.resolve(GREETING_SOURCE));
            breakTheSources(mvn, log, project, source, 1);
            assertGreets(Fixture.get(client, Fixture.served(log) + GREETING), "while the sources do not compile", log);

            // A second layer: the source compiles again with one more method, which swaps the layer. No request
            // reaches the new layer before its sources are broken in turn.
            String grown = source.substring(0, source.lastIndexOf('}')) + """
                        public String shout() {
                            return name.toUpperCase();
                        }
                    }
                    """;
            Files.writeString(project.resolve(GREETING_SOURCE), grown);
            Fixture.awaitOccurrences(mvn, log, STARTED, 2);
            breakTheSources(mvn, log, project, grown, 2);
            assertGreets(Fixture.get(client, Fixture.served(log) + GREETING),
                    "while the sources do not compile, on the layer a swap started", log);
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * The control, and the switch from the goal to the application: with {@code -Dvidocq.dev.preloadClasses=false}
     * each class is read from the output when it is first used, and the resource fails as it did before the fix. It
     * also keeps the premise of the test above: the first request needs a class the start did not load.
     */
    @Test
    void withTheClassesLeftToTheirFirstUseTheResourceFailsWhileTheSourcesDoNotCompile(@TempDir Path dir)
            throws Exception {
        Path project = built(dir, "lazy-classes");
        Path log = Fixture.logs().resolve("lazy-classes-dev.log");

        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.devconsole.port=0", "-Dvidocq.dev.preloadClasses=false");
        try (HttpClient client = HttpClient.newHttpClient()) {
            Fixture.awaitOccurrences(mvn, log, STARTED, 1);
            breakTheSources(mvn, log, project, Files.readString(project.resolve(GREETING_SOURCE)), 1);

            HttpResponse<String> response = Fixture.get(client, Fixture.served(log) + GREETING);
            assertEquals(500, response.statusCode(), () -> "the premise of these tests is gone: with the classes left"
                    + " to their first use, the resource answers while the sources do not compile: " + response.body()
                    + "\n" + Fixture.read(log));
            Fixture.awaitLog(mvn, log, "java.lang.NoClassDefFoundError: acme/reload/");
        } finally {
            Fixture.stop(mvn);
        }
    }

    /** The {@code reload} fixture, copied and built: its classes are in the output, nothing runs. */
    private static Path built(Path dir, String name) throws Exception {
        Path project = Fixture.copy(dir).resolve("reload");
        Path build = Fixture.logs().resolve(name + "-build.log");
        Fixture.awaitSuccess(Fixture.start(project, build, "process-classes"), build, "the build of the application");
        return project;
    }

    /**
     * Saves {@code source} with an error, waits for the recompile to fail for the {@code failures}-th time, and checks
     * that the classes left the output.
     */
    private static void breakTheSources(Process mvn, Path log, Path project, String source, int failures)
            throws Exception {
        Files.writeString(project.resolve(GREETING_SOURCE), source + "a line that does not compile\n");
        Fixture.awaitOccurrences(mvn, log, COMPILE_FAILED, failures);
        assertFalse(Files.exists(project.resolve("target/classes/acme/reload/GreetingResource.class")),
                () -> "the premise of this test is gone: the compiler plugin no longer removes the classes it"
                        + " created when javac fails.\n" + Fixture.read(log));
    }

    private static void assertGreets(HttpResponse<String> response, String when, Path log) {
        assertEquals(200, response.statusCode(),
                () -> "the resource does not answer " + when + ": " + response.body() + "\n" + Fixture.read(log));
        assertTrue(response.body().contains("\"salutation\":\"Hello\"") && response.body().contains("\"name\":\"Ada\""),
                response::body);
    }
}
