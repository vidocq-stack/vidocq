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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vauban BUG-20261117-12 (Vidocq/vauban#324), seen from {@code vidocq:dev}: a change of a method body is redefined
 * in place in an application whose build writes synthetic metadata. A servlet application is one: Foy's build
 * compatible extension registers a synthetic bean that lists the web components, and the processor writes it to
 * {@code META-INF/vauban-synthetic-metadata.properties}. That file held the date of the build, so every recompile
 * changed it and every save swapped the layer: the application started again and lost its state.
 *
 * <p>The servlet of the fixture counts its requests in a field. After a change of its method body it answers with
 * the new text and goes on counting, in the application that started once.
 */
class AppLayerServletHotSwapIT {

    /** The line a boot logs once the application serves. */
    private static final String STARTED = "Vidocq - Started in";

    /** The line the dev loop logs once it knows what a recompile changed, its decision between parentheses. */
    private static final String SIGNALLED = "Hot reload signalled after ";

    private static final String SYNTHETIC_METADATA = "target/classes/META-INF/vauban-synthetic-metadata.properties";

    @Test
    void aChangeOfAMethodBodyOfAServletIsRedefinedInPlace(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir).resolve("servlet");
        Path build = Fixture.logs().resolve("servlet-build.log");
        Fixture.awaitSuccess(Fixture.start(project, build, "process-classes"), build, "the build of the application");
        assertTrue(Files.exists(project.resolve(SYNTHETIC_METADATA)), () -> "the premise of this test is gone: the"
                + " build of a servlet application no longer writes " + SYNTHETIC_METADATA + ":\n"
                + Fixture.read(build));
        Path log = Fixture.logs().resolve("servlet-dev.log");

        // No debug agent and a free port for the dev console, as AppLayerReloadLeakIT.
        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.devconsole.port=0");
        try (HttpClient client = HttpClient.newHttpClient()) {
            Fixture.awaitOccurrences(mvn, log, STARTED, 1);
            assertEquals("Hello Ada, request 1", greeting(client, log));
            assertEquals(0, Fixture.occurrences(log, SIGNALLED), () -> "a reload before any change:\n"
                    + Fixture.read(log));

            Path servlet = project.resolve("src/main/java/acme/servlet/HelloServlet.java");
            Files.writeString(servlet, Files.readString(servlet).replace("\"Hello \"", "\"Good evening \""));
            Fixture.awaitLog(mvn, log, SIGNALLED);
            String decision = decision(log);
            assertTrue(decision.contains("(class redefinition: 1 class)"), () -> "the change of a method body of the"
                    + " servlet was not redefined in place: " + decision + "\n" + Fixture.read(log));
            Fixture.awaitLog(mvn, log, "Hot reload - redefined 1 class in ");

            assertEquals("Good evening Ada, request 2", greeting(client, log));
            assertEquals(1, Fixture.occurrences(log, STARTED), () -> "the application started again:\n"
                    + Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    /** The first decision of the dev loop: {@code Hot reload signalled after 412 ms (class redefinition: 1 class).} */
    private static String decision(Path log) {
        return Fixture.read(log).lines().filter(line -> line.contains(SIGNALLED)).findFirst().orElseThrow();
    }

    /** What the servlet answers: its greeting, and the number of requests it served since the application started. */
    private static String greeting(HttpClient client, Path log) throws Exception {
        HttpResponse<String> response = Fixture.get(client, Fixture.served(log) + "/hello?name=Ada");
        assertEquals(200, response.statusCode(), () -> response.body() + "\n" + Fixture.read(log));
        return response.body().strip();
    }
}
