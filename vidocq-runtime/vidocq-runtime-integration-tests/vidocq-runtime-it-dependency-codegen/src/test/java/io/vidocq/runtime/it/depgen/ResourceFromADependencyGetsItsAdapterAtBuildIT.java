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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JAX-RS resource class of a plain dependency jar, compiled without {@code cassini-processor}, gets its Cassini
 * adapter at build: {@code vidocq:generate} runs the processor that the REST codegen bundle's closure holds, with
 * the hints that {@code cassini-processor} ships, so the start generates nothing for it (Vidocq/vidocq#662,
 * BUG-20261118-05).
 */
class ResourceFromADependencyGetsItsAdapterAtBuildIT {

    private static final String FIXTURE = "it.fixture.rest";

    private static final String FALLBACK = "CASSINI — RUNTIME CODE GENERATION FALLBACK";

    private static final String NO_CHECKPOM = "-Dvidocq.checkpom.failOnMissing=false";

    /** The system picks the port; the start logs it (BUG-20261114-09). */
    private static final String ANY_PORT = "-Dvidocq.chappe.listener.default.port=0";

    private static final Pattern LISTENING =
            Pattern.compile("Chappe listener 'default' started on http://[^/]+:(\\d+)/");

    @Test
    void theAdapterIsWrittenAtBuildAndTheStartGeneratesNothing(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir, FIXTURE);
        String cache = Fixture.cacheDir("rest");
        Path install = Fixture.logs().resolve("rest-install.log");
        Fixture.install(project, install, cache);
        List<String> generated = Fixture.lines(install, "vidocq:generate: ", "restlib", "— Cassini (");
        assertEquals(1, generated.size(), Fixture.read(install));
        assertTrue(generated.getFirst().contains("generated)"), generated.getFirst());

        Path log = Fixture.logs().resolve("rest-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", cache, ANY_PORT);
        try {
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            assertEquals("Hello world", get(log, "/hello/world"));
            assertFalse(Fixture.read(log).contains(FALLBACK), Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    @Test
    void withoutTheCodegenBundleTheStartGeneratesTheAdapter(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir, FIXTURE);
        Fixture.withoutCodegenBundle(project);
        String cache = Fixture.cacheDir("rest-control");
        Path install = Fixture.logs().resolve("rest-control-install.log");
        // vidocq:checkpom fails a build that declares the extension without its codegen bundle: the point here.
        Fixture.awaitSuccess(Fixture.start(project, install, "install", "-DskipTests", cache, NO_CHECKPOM), install,
                "the fixture build");
        assertEquals(List.of(), Fixture.lines(install, "vidocq:generate: ", "— Cassini ("));

        Path log = Fixture.logs().resolve("rest-control-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", cache, NO_CHECKPOM, ANY_PORT);
        try {
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            assertEquals("Hello world", get(log, "/hello/world"));
            String run = Fixture.read(log);
            int fallback = run.indexOf(FALLBACK);
            assertTrue(fallback >= 0, run);
            assertTrue(run.substring(fallback).contains("  - acme.restlib.HelloResource"), run);
        } finally {
            Fixture.stop(mvn);
        }
    }

    private static String get(Path log, String path) throws Exception {
        Matcher listening = LISTENING.matcher(Fixture.read(log));
        assertTrue(listening.find(), Fixture.read(log));
        URI uri = URI.create("http://127.0.0.1:" + listening.group(1) + path);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).timeout(Fixture.TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), () -> response.body() + "\n" + Fixture.read(log));
            return response.body();
        }
    }
}
