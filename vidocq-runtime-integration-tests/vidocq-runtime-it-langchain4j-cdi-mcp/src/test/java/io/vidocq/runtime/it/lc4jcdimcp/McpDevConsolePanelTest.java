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
            DevConsoleSnapshot before = DevConsoleSnapshot.read(server.snapshotUrl());
            before.assertSampled();

            assertEquals("no request served yet", before.absent("sessions"),
                    "before any request the panel must say why it has no number, never write a zero");
            assertEquals("built at the first call", before.absent("invoker.methods"));
            // These two exist from the boot: their zero is a measure, not a missing value.
            assertEquals(0.0, before.number("streams"));
            assertEquals(0.0, before.number("listens"));

            McpCalls calls = new McpCalls(server.mcpUrl());
            calls.initialize();
            calls.runAll();

            DevConsoleSnapshot after = DevConsoleSnapshot.read(server.snapshotUrl());
            after.assertSampled();

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
