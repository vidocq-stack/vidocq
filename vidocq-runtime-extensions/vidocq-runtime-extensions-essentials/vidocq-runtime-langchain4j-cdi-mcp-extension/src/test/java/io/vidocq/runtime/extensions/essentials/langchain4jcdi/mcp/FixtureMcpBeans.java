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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

import dev.langchain4j.cdi.mcp.server.api.McpLog;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

/**
 * MCP methods to fill the registries of a test. This module compiles without {@code -parameters}, so a parameter
 * without an explicit name here has no name at run time.
 */
final class FixtureMcpBeans {

    private FixtureMcpBeans() {}

    /** Every argument named explicitly, one framework parameter: nothing to report. */
    static final class Named {

        @Tool(name = "current_time", description = "The current time.")
        public String currentTime(@ToolArg(name = "zone") String zone, McpLog log) {
            return zone;
        }

        @Tool(name = "convert_time", description = "Converts a time.")
        public String convertTime(@ToolArg(name = "time") String time, @ToolArg(name = "zone") String zone) {
            return time + zone;
        }

        @Prompt(name = "plan_meeting", description = "Plans a meeting.")
        public String planMeeting(@PromptArg(name = "zones") String zones) {
            return zones;
        }

        @Resource(uri = "time://utc", name = "utc", description = "UTC.")
        public String utc() {
            return "UTC";
        }

        @ResourceTemplate(uriTemplate = "time://zone/{zone}", name = "time-in-zone", description = "A zone.")
        public String timeInZone(@ResourceTemplateArg(name = "zone") String zone) {
            return zone;
        }
    }

    /** Arguments without a name: {@code VIDOCQ-MCP-005}. */
    static final class Unnamed {

        @Tool(name = "echo", description = "Echoes.")
        public String echo(String text) {
            return text;
        }

        @Prompt(name = "greet", description = "Greets.")
        public String greet(@PromptArg(description = "who") String who) {
            return who;
        }
    }
}
