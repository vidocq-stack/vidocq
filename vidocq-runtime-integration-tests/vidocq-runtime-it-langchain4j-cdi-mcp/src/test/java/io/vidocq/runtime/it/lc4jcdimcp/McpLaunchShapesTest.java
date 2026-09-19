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
                "-Dvidocq.mcp.requestStateSecret=" + SECRET)) {
            String initialize = new McpCalls(server.mcpUrl()).initialize();
            String log = server.log();

            assertTrue(initialize.contains("\"serverInfo\":{\"name\":\"it-server\",\"version\":\"9.9.9\"}"),
                    initialize);
            assertTrue(log.contains("it-server 9.9.9 (vidocq.mcp.*)"), log);
            assertTrue(log.lines().anyMatch(line -> line.strip().matches("requestStateSecret\\s+configured")), log);
            assertFalse(log.contains(SECRET), "the rendered startup report printed the request-state secret");
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
            assertTrue(log.contains("loaded twice"), log);
        }
    }
}
