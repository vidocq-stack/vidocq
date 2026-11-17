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
 * Vidocq/vidocq#631: a hot reload releases the application layer it discards. {@code vidocq:dev} swaps the layer of
 * an application several times; each boot serves the REST resource, which reads the configuration and writes JSON,
 * the method the reload added, and a file of the static mount. The child JVM then holds one layer, as after its
 * first boot: a layer that anything in the runtime still reaches would add its loader to the count. The application
 * has no test of its own, so {@code vidocq:dev} runs no background test next to it.
 *
 * <p>{@link AppLayerDevIT} is the control: its fixture keeps each discarded layer alive through a thread, and the
 * same count reads two there after one reload.
 */
class AppLayerReloadLeakIT {

    /** Enough swaps to tell a layer kept at each reload from one loader more for another reason. */
    private static final int RELOADS = 5;

    /** The line a boot logs once the application serves. */
    private static final String STARTED = "Vidocq - Started in";

    /** The line the dev loop logs once it stopped a deployment to swap its layer, in the same JVM. */
    private static final String SWAPPED = "Hot reload - previous deployment stopped in";

    @Test
    void theLayersOfTheReloadsAreCollected(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir).resolve("reload");
        Path build = Fixture.logs().resolve("reload-build.log");
        Fixture.awaitSuccess(Fixture.start(project, build, "process-classes"), build, "the build of the application");
        Path log = Fixture.logs().resolve("reload-dev.log");

        // No debug agent and a free port for the dev console: never 5005 nor 8888, which a dev run next to this
        // test may hold. The listener of the application binds port 0 in its vidocq.properties.
        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.devconsole.port=0");
        try (HttpClient client = HttpClient.newHttpClient()) {
            Fixture.awaitOccurrences(mvn, log, STARTED, 1);
            long pid = LayerLoaders.childPid(log);
            serve(client, log, 0);
            assertEquals(1, LayerLoaders.settled(mvn, pid, 1),
                    () -> "the first boot does not leave one application layer:\n" + Fixture.read(log));

            Path resource = project.resolve("src/main/java/acme/reload/GreetingResource.java");
            for (int reload = 1; reload <= RELOADS; reload++) {
                // A new resource method changes the shape of the class: nothing redefines it in place, the layer is
                // swapped, and the next boot must serve the method.
                String source = Files.readString(resource);
                Files.writeString(resource, source.substring(0, source.lastIndexOf('}')) + """

                            @GET
                            @Path("/reload%1$d")
                            @Produces(MediaType.TEXT_PLAIN)
                            public String reload%1$d() {
                                return "reload %1$d";
                            }
                        }
                        """.formatted(reload));
                Fixture.awaitOccurrences(mvn, log, STARTED, reload + 1);
                serve(client, log, reload);
            }
            assertEquals(RELOADS, Fixture.occurrences(log, SWAPPED),
                    () -> "not every reload swapped the layer in the same JVM:\n" + Fixture.read(log));

            int live = LayerLoaders.settled(mvn, pid, 1);
            assertEquals(1, live, () -> live + " application layers are alive after " + RELOADS + " reloads, one is"
                    + " expected: a hot reload keeps the layers it discards. A heap dump of the child JVM (jcmd <pid>"
                    + " GC.heap_dump) shows what reaches a " + LayerLoaders.LOADER + ".\n" + Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * What the boot that printed the last listener line serves: the resource, with the configuration value in its
     * JSON; the method the reload {@code reload} added, none for the first boot; a file of the static mount.
     */
    private static void serve(HttpClient client, Path log, int reload) throws Exception {
        String base = Fixture.served(log);
        String greeting = get(client, base + "/api/greeting?name=Ada", log);
        assertTrue(greeting.contains("\"salutation\":\"Hello\"") && greeting.contains("\"name\":\"Ada\""), greeting);
        if (reload > 0) {
            assertEquals("reload " + reload, get(client, base + "/api/greeting/reload" + reload, log));
        }
        assertTrue(get(client, base + "/index.html", log).contains("a static page"));
    }

    private static String get(HttpClient client, String url, Path log) throws Exception {
        HttpResponse<String> response = Fixture.get(client, url);
        assertEquals(200, response.statusCode(), () -> url + ": " + response.body() + "\n" + Fixture.read(log));
        return response.body().strip();
    }
}
