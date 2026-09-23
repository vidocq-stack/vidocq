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
package io.vidocq.runtime.it.lc4jcdimcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I1 to I3 of Vidocq/vidocq#94: the MCP server of an application that depends on the langchain4j-cdi MCP extension
 * alone answers the six calls in every launch shape, with only the application module in the application layer. I2
 * also checks U4 on the rendered report: the request-state secret is said to be configured, never printed.
 */
class McpLaunchShapesTest {

    private static final String LAYER_OF_THE_APP_ALONE =
            "Application layer ready: modules [" + LaunchedServer.APP_MODULE + "] (";
    /** 40 bytes: langchain4j-cdi accepts it (at least 32). */
    private static final String SECRET = "it-request-state-secret-of-40-characters";

    @ParameterizedTest
    @EnumSource(LaunchedServer.Shape.class)
    void theSixCallsPassAndOnlyTheApplicationMoves(LaunchedServer.Shape shape) throws Exception {
        try (LaunchedServer server = LaunchedServer.start(shape, "i1-" + shape.name().toLowerCase())) {
            McpCalls calls = new McpCalls(server.mcpUrl());
            calls.initialize();
            calls.runAll();

            String log = server.log();
            assertTrue(log.contains(LAYER_OF_THE_APP_ALONE), log);
            assertTrue(log.contains("2 tools, 1 prompt, 0 resources, 1 resource template"), log);
            assertFalse(log.contains("No McpServerSPI implementation found"), log);
            assertFalse(log.contains("[VIDOCQ-MCP-"), log);
        }
    }

    @Test
    void theServerKeysReachTheInitializeResponseAndTheSecretIsNeverPrinted() throws Exception {
        try (LaunchedServer server = LaunchedServer.start(LaunchedServer.Shape.FLAT, "i2-keys",
                "-Dvidocq.mcp.serverName=it-server", "-Dvidocq.mcp.serverVersion=9.9.9",
                "-Dvidocq.mcp.requestStateSecret=" + SECRET,
                // Vidocq/vidocq#116: a dev launch, so that the config panel shows values, and a mistyped key
                "-Dvidocq.launch.mode=dev", "-Dvidocq.mcp.serverNam=typo")) {
            String initialize = new McpCalls(server.mcpUrl()).initialize();
            String log = server.log();

            assertTrue(initialize.contains("\"serverInfo\":{\"name\":\"it-server\",\"version\":\"9.9.9\"}"),
                    initialize);
            assertTrue(log.contains("it-server 9.9.9 (vidocq.mcp.*)"), log);
            assertTrue(log.lines().anyMatch(line -> line.strip().matches("requestStateSecret\\s+configured")), log);
            assertFalse(log.contains(SECRET), "the rendered startup report printed the request-state secret");
            // vidocq#103: the rest section lists the routes Cassini resolved, /mcp among them; vidocq#111: as absolute
            // URLs, since the http section declares the listener they are on.
            assertTrue(log.lines().anyMatch(line -> line.strip()
                    .matches("POST\\s+" + java.util.regex.Pattern.quote(server.mcpUrl()) + "\\s+McpEndpoint#handlePost")),
                    log);
            assertTrue(log.lines().anyMatch(line -> line.strip()
                    .equals("endpoint            " + server.mcpUrl())), log);

            // Vidocq/vidocq#116: the dev console's config panel lists the secret key, from the system properties,
            // as configured; the other keys with their value; the mistyped key as unused. The secret itself
            // appears nowhere in the snapshot.
            DevConsoleSnapshot snapshot = DevConsoleSnapshot.read(server.snapshotUrl());
            String config = snapshot.configPanel();
            assertTrue(config.contains("[\"vidocq.mcp.requestStateSecret\",\"SystemProperties\",\"400\","
                    + "\"configured\",\"claimed\"]"), config);
            assertTrue(config.contains("[\"vidocq.mcp.serverName\",\"SystemProperties\",\"400\",\"it-server\","
                    + "\"claimed\"]"), config);
            assertTrue(config.contains("[\"vidocq.mcp.serverNam\",\"SystemProperties\",\"400\",\"typo\","
                    + "\"unused\"]"), config);
            assertFalse(snapshot.json().contains(SECRET), "the dev console's snapshot holds the request-state secret");

            // Vidocq/vidocq#118: a dev launch publishes the token of its boot for the console's own page, and an
            // action request from another site is refused before anything is looked up, even with that token.
            String token = snapshot.actionToken();
            assertTrue(token != null && token.matches("[0-9a-f]{64}"), "no action token in a dev launch: " + token);
            String own = URI.create(server.snapshotUrl()).resolve("/").toString().replaceAll("/$", "");
            assertEquals(403, DevConsoleSnapshot.postAction(server.snapshotUrl(), "/api/action/mcp/nothing",
                    "https://evil.example.com", token));
            assertEquals(403, DevConsoleSnapshot.postAction(server.snapshotUrl(), "/api/action/mcp/nothing", own,
                    "0".repeat(64)));
            // its own origin and the token get past both checks, to an action the mcp panel does not offer
            assertEquals(404, DevConsoleSnapshot.postAction(server.snapshotUrl(), "/api/action/mcp/nothing", own,
                    token));
            assertTrue(server.log().contains("[VIDOCQ-DEVC-006]"), "the refusal is logged");

            // Vidocq/vidocq#119: the logs panel of a dev launch shows the records of this boot, its last line among
            // them, and its set-level action changes a level the levels table then lists, until the next reload.
            String logs = DevConsoleSnapshot.read(server.snapshotUrl()).logsPanel();
            assertTrue(logs.contains("Vidocq - Started in "), "the logs panel shows no record of the boot: " + logs);
            HttpResponse<String> set = DevConsoleSnapshot.postAction(server.snapshotUrl(),
                    "/api/action/logs/set-level", own, token, "{\"logger\":\"io.vidocq.cassini\",\"level\":\"FINE\"}");
            assertEquals(200, set.statusCode(), set.body());
            assertTrue(set.body().contains("io.vidocq.cassini at FINE until the next reload"), set.body());
            String levels = DevConsoleSnapshot.read(server.snapshotUrl()).logsPanel();
            assertTrue(levels.contains("[\"io.vidocq.cassini\",\"FINE\",\"until the next reload\"]"),
                    "the levels table does not show the level set: " + levels);
        }
    }

    @Test
    void forcingTheServerIntoTheApplicationLayerIsReportedAndStillServes() throws Exception {
        try (LaunchedServer server = LaunchedServer.start(LaunchedServer.Shape.FLAT, "i3-forced",
                "-Dvidocq.app.modules=" + LaunchedServer.APP_MODULE
                        + ",dev.langchain4j.cdi.mcp.server,dev.langchain4j.cdi.mcp.invoker.cdi41")) {
            McpCalls calls = new McpCalls(server.mcpUrl());
            calls.initialize();
            calls.runAll();

            String log = server.log();
            assertTrue(log.contains("[VIDOCQ-MCP-003]"), log);
            assertTrue(log.lines().anyMatch(line -> line.strip().equals("loaded twice")), log);
        }
    }
}
