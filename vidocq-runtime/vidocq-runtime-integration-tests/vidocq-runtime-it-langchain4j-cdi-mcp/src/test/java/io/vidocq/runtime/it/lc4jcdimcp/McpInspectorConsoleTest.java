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

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP inspector of the dev console on a real server (spec §5): the catalogue among the {@code mcp} panel's
 * actions, a tool called through {@code POST /api/action/mcp/tool.<id>} with the snapshot's token, a prompt, a
 * resource template, and the history the next snapshot shows. Every call goes through the application's real
 * {@code /mcp}, in protocol 2026-07-28, without a session.
 */
class McpInspectorConsoleTest {

    @Test
    void aToolAPromptAndAResourceAreCalledFromTheConsoleAndListedInTheHistory() throws Exception {
        try (LaunchedServer server = LaunchedServer.start(LaunchedServer.Shape.FLAT, "inspector",
                "-Dvidocq.launch.mode=dev")) {
            DevConsoleSnapshot snapshot = DevConsoleSnapshot.read(server.snapshotUrl());
            String token = snapshot.actionToken();
            assertTrue(token != null && token.matches("[0-9a-f]{64}"), "no action token in a dev launch: " + token);
            String own = URI.create(server.snapshotUrl()).resolve("/").toString().replaceAll("/$", "");
            String template = "tpl." + sha8("time://zone/{zone}");
            String json = snapshot.json();

            for (String id : new String[] {"tool.current-time", "tool.convert-time", "prompt.plan-meeting", template}) {
                assertTrue(json.contains("\"id\":\"" + id + "\""), id + " missing from " + json);
            }
            assertTrue(json.contains("\"group\":\"Tools\""), json);
            assertTrue(json.contains("\"confirmation\":\"Call current_time? It runs the application's code, and may "
                    + "change data.\""), json);

            HttpResponse<String> tool = DevConsoleSnapshot.postAction(server.snapshotUrl(),
                    "/api/action/mcp/tool.current-time", own, token,
                    "{\"arguments\":\"{\\\"zone\\\":\\\"Europe/Paris\\\"}\"}");
            assertEquals(200, tool.statusCode(), tool.body());
            assertTrue(tool.body().matches("\\{\"result\":\"ok in [0-9]+ ms\",\"contentType\":\"text/plain\","
                    + "\"body\":\"[^\"]*Europe/Paris[^\"]*\",\"details\":.*"), tool.body());
            assertTrue(tool.body().contains("tools/call"), tool.body());
            assertFalse(tool.body().contains("\"error\":true"), tool.body());

            HttpResponse<String> prompt = DevConsoleSnapshot.postAction(server.snapshotUrl(),
                    "/api/action/mcp/prompt.plan-meeting", own, token,
                    "{\"arguments\":\"{\\\"zones\\\":\\\"Europe/Paris,Asia/Tokyo\\\","
                            + "\\\"durationMinutes\\\":\\\"30\\\"}\"}");
            assertEquals(200, prompt.statusCode(), prompt.body());
            assertTrue(prompt.body().matches("\\{\"result\":\"1 message\\(s\\) in [0-9]+ ms\".*"), prompt.body());
            assertTrue(prompt.body().contains("Propose a 30-minute meeting slot"), prompt.body());

            HttpResponse<String> resource = DevConsoleSnapshot.postAction(server.snapshotUrl(),
                    "/api/action/mcp/" + template, own, token,
                    "{\"variables\":\"{\\\"zone\\\":\\\"Europe/Paris\\\"}\"}");
            assertEquals(200, resource.statusCode(), resource.body());
            assertTrue(resource.body().matches("\\{\"result\":\"1 content item\\(s\\) in [0-9]+ ms\".*"),
                    resource.body());
            assertTrue(resource.body().contains("Europe/Paris"), resource.body());

            String after = DevConsoleSnapshot.read(server.snapshotUrl()).json();
            assertTrue(after.contains("\"key\":\"calls\""), "no history: " + after);
            assertTrue(after.contains("tool.current-time {\\\"arguments\\\":{\\\"zone\\\":\\\"Europe/Paris\\\"}}"),
                    "no replay of the tool call: " + after);
            assertTrue(after.contains(template + " {\\\"variables\\\":{\\\"zone\\\":\\\"Europe/Paris\\\"}}"),
                    "no replay of the resource read: " + after);
            assertTrue(after.contains("2 tools, 1 prompt, 0 resources, 1 resource template at http://127.0.0.1:"),
                    "the inspector does not say where it calls: " + after);
        }
    }

    private static String sha8(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)), 0, 4);
    }
}
