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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import dev.langchain4j.cdi.mcp.server.registry.McpPromptDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceTemplateDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import dev.langchain4j.cdi.mcp.server.transport.McpNotificationBroadcaster;
import dev.langchain4j.cdi.mcp.server.transport.McpResourceSubscriptionManager;
import dev.langchain4j.cdi.mcp.server.transport.McpRootsManager;
import dev.langchain4j.cdi.mcp.server.transport.McpServerRequestManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSessionManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSubscriptionRegistry;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import java.lang.reflect.Method;
import java.util.List;

/**
 * MCP methods and registries for the inspector's tests. This module compiles without {@code -parameters}: every
 * argument is named explicitly.
 */
final class InspectorFixtures {

    /** The registries, and every bean their injection points need. */
    static final Class<?>[] CATALOGUE_BEANS = {
        McpToolRegistry.class, McpPromptRegistry.class, McpResourceRegistry.class,
        McpSessionManager.class, McpNotificationBroadcaster.class, McpSubscriptionRegistry.class,
        McpServerRequestManager.class, McpResourceSubscriptionManager.class, McpRootsManager.class
    };

    private InspectorFixtures() {}

    static final class Tools {

        @Tool(name = "current_time", title = "Current time", description = "The current time in a zone.",
                annotations = @Tool.Annotations(readOnlyHint = true))
        public String currentTime(@ToolArg(name = "zone", description = "IANA zone id") String zone) {
            return zone;
        }

        @Tool(name = "current-time", description = "The same key as current_time once turned into an id.",
                annotations = @Tool.Annotations(readOnlyHint = true))
        public String currentTimeHyphen() {
            return "now";
        }

        @Tool(name = "reset_counter", description = "Resets the counter.")
        public String resetCounter() {
            return "0";
        }

        @Tool(name = "a_tool_whose_name_is_far_longer_than_forty_characters", description = "Long.",
                annotations = @Tool.Annotations(destructiveHint = false))
        public String longName() {
            return "long";
        }

        @Tool(name = "search", description = "Searches with a key.")
        public String search(@ToolArg(name = "query") String query, @ToolArg(name = "apiKey") String apiKey) {
            return query;
        }
    }

    static final class Prompts {

        @Prompt(name = "plan_meeting", description = "Plans a meeting.")
        public String planMeeting(@PromptArg(name = "zones", description = "Comma-separated zones") String zones,
                                  @PromptArg(name = "note", required = false) String note) {
            return zones;
        }
    }

    static final class Resources {

        @Resource(uri = "time://utc", name = "utc", description = "UTC.")
        public String utc() {
            return "UTC";
        }

        @ResourceTemplate(uriTemplate = "time://zone/{zone}", name = "time-in-zone", description = "A zone.")
        public String timeInZone(@ResourceTemplateArg(name = "zone") String zone) {
            return zone;
        }
    }

    static McpToolDescriptor tool(String method) {
        return McpToolDescriptor.fromMethod(Tools.class, method(Tools.class, method));
    }

    static McpPromptDescriptor prompt(String method) {
        return McpPromptDescriptor.fromMethod(Prompts.class, method(Prompts.class, method));
    }

    static McpResourceDescriptor resource(String method) {
        return McpResourceDescriptor.fromMethod(Resources.class, method(Resources.class, method));
    }

    static McpResourceTemplateDescriptor template(String method) {
        return McpResourceTemplateDescriptor.fromMethod(Resources.class, method(Resources.class, method));
    }

    /** Creates the three registries through their client proxies, as the server does, and fills them. */
    static void fill(BeanManager beans) {
        McpToolRegistry tools = reference(beans, McpToolRegistry.class);
        for (String method : List.of("currentTime", "currentTimeHyphen", "resetCounter", "longName", "search")) {
            tools.register(tool(method));
        }
        reference(beans, McpPromptRegistry.class).register(prompt("planMeeting"));
        McpResourceRegistry resources = reference(beans, McpResourceRegistry.class);
        resources.register(resource("utc"));
        resources.registerTemplate(template("timeInZone"));
    }

    static <T> T reference(BeanManager beans, Class<T> type) {
        Bean<?> bean = beans.resolve(beans.getBeans(type));
        return type.cast(beans.getReference(bean, type, beans.createCreationalContext(bean)));
    }

    private static Method method(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalArgumentException(name);
    }
}
