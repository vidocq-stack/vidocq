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

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I4 of Vidocq/vidocq#94: the {@code mcp} panel of the dev console, on a real server, read as its page reads it —
 * two {@code GET /api/snapshot}, one before the first MCP request and one after the six calls.
 *
 * <p>What moves between the two is what the server really did, because the panel creates nothing: the session the
 * client opened, and the invoker provider the first tool call built. What did not happen stays absent with its
 * reason rather than reading as a zero — nothing was ever asked of the client, so {@code pending} has no number to
 * give. The two counters of the provider count distinct methods, not calls: six calls over four MCP methods.
 */
class McpDevConsolePanelTest {

    @Test
    void theSessionGaugeIsAbsentBeforeTheFirstRequestAndCountsAfterIt() throws Exception {
        try (LaunchedServer server = LaunchedServer.start(LaunchedServer.Shape.FLAT, "i4-devconsole")) {
            // Warm: a snapshot creates nothing, so a first poll changes none of the values checked below.
            DevConsoleSnapshot before = DevConsoleSnapshot.readWarm(server.snapshotUrl());
            before.assertSampled();
            double restBefore = before.restRequests();

            assertEquals("no request served yet", before.absent("sessions"),
                    "before any request the panel must say why it has no number, never write a zero");
            assertEquals("built at the first call", before.absent("invoker.methods"));
            // These two exist from the boot: their zero is a measure, not a missing value.
            assertEquals(0.0, before.number("streams"));
            assertEquals(0.0, before.number("listens"));

            // Vidocq/vidocq#117: the console's own cdi panel lists the MCP endpoint and the application's tools
            // among the beans, read from Vauban's metadata, the application's rows first.
            String cdi = before.cdiPanel();
            assertTrue(cdi.matches("(?s).*\"summary\":\"[0-9]+ beans \\(.*, [0-9]+ interceptors?, 0 decorators, "
                    + "[0-9]+ observers?\".*"), cdi);
            assertTrue(cdi.contains("\"dev.langchain4j.cdi.mcp.server.transport.McpEndpoint\""),
                    "no McpEndpoint bean: " + cdi);
            assertTrue(cdi.contains("[\"io.vidocq.runtime.it.lc4jcdimcp.TimeTools\","), "no TimeTools bean: " + cdi);
            int runtime = cdi.indexOf("\"runtime\"]");
            assertTrue(runtime < 0 || cdi.indexOf("\"application\"]") < runtime,
                    "the application's beans come first: " + cdi);
            // Vidocq/vidocq#186: vidocq:generate scanned the langchain4j-cdi jars the extension declares, and the
            // launched JVM runs their enriched copies, so their beans have build-time code instead of reflection.
            List<String> mcpRows = Pattern.compile("\\[\"dev\\.langchain4j\\.cdi\\.mcp\\.[^\\[\\]]*]").matcher(cdi)
                    .results().map(MatchResult::group).toList();
            assertTrue(mcpRows.stream().anyMatch(row -> row.startsWith(
                    "[\"dev.langchain4j.cdi.mcp.server.transport.McpEndpoint\",")
                    && row.matches(".*\"library\",\"(Class-File|partial)\".*")), "McpEndpoint: " + mcpRows);
            // Vidocq/vidocq#188: the three JAX-RS providers too, which carry @Provider alone: Cassini's
            // build-compatible extension makes them beans, and vidocq:generate sees it although it is a
            // runtime-scope dependency.
            assertEquals(List.of(),
                    mcpRows.stream().filter(row -> row.contains("\"library\",\"reflection\""))
                            .map(row -> row.substring(row.lastIndexOf('.', row.indexOf("\",")) + 1,
                                    row.indexOf("\",")))
                            .sorted().toList(),
                    "the langchain4j-cdi beans still created by reflection: " + mcpRows);

            McpCalls calls = new McpCalls(server.mcpUrl());
            calls.initialize();
            calls.runAll();

            // Warm again: the first poll after the calls samples beans that did not exist before, on cold code.
            DevConsoleSnapshot after = DevConsoleSnapshot.readWarm(server.snapshotUrl());
            after.assertSampled();
            // The six MCP calls and the initialize went through Cassini: the rest panel counted them.
            assertTrue(after.restRequests() >= restBefore + 7, "the rest panel did not count the MCP calls");

            assertEquals(1.0, after.number("sessions"), "the session the client opened is not counted");
            assertEquals(0.0, after.number("streams"));
            assertEquals(0.0, after.number("listens"));
            // Nothing was asked of the client: absent, not a zero the panel cannot vouch for.
            assertEquals("not created yet", after.absent("pending"));
            // The CDI 4.1 invoker: four MCP methods, all four resolved, none fell back to reflection.
            assertEquals(4.0, after.number("invoker.methods"));
            assertEquals(4.0, after.number("invoker.matches"));
            assertEquals(0.0, after.number("invoker.misses"));
            // A gauge, its unit, and nothing else: no session id, no tool name, no header reaches the page.
            assertTrue(after.value("sessions").matches("\\{\"key\":\"sessions\",\"kind\":\"gauge\",\"value\":[0-9.]+"
                    + ",\"unit\":\"count\"\\}"), after.value("sessions"));
        }
    }
}
