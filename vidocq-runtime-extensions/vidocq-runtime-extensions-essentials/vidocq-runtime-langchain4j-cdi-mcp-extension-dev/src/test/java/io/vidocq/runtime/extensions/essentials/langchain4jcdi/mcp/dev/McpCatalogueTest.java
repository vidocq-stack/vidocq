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

import dev.langchain4j.cdi.mcp.server.protocol.McpToolAnnotationsModel;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the inspector offers, read from langchain4j-cdi's registries without creating any. */
class McpCatalogueTest {

    private static McpCatalogue all() {
        return McpCatalogue.of(
                List.of(InspectorFixtures.tool("resetCounter"), InspectorFixtures.tool("currentTime"),
                        InspectorFixtures.tool("currentTimeHyphen"), InspectorFixtures.tool("longName"),
                        InspectorFixtures.tool("search")),
                List.of(InspectorFixtures.prompt("planMeeting")),
                List.of(InspectorFixtures.resource("utc")),
                List.of(InspectorFixtures.template("timeInZone")));
    }

    private static McpCatalogue.Item item(McpCatalogue catalogue, String id) {
        return catalogue.items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void eachToolPromptResourceAndTemplateIsAnItemOfItsGroupInNameOrder() {
        McpCatalogue catalogue = all();

        assertEquals(List.of("tool.a-tool-whose-name-is-far-longer-tha", "tool.current-time", "tool.current-time-2",
                "tool.reset-counter", "tool.search", "prompt.plan-meeting", "res.860cd4a5", "tpl.72bba68d"),
                catalogue.items().stream().map(McpCatalogue.Item::id).toList());
        assertEquals(List.of("Tools", "Tools", "Tools", "Tools", "Tools", "Prompts", "Resources", "Resources"),
                catalogue.items().stream().map(item -> item.kind().group()).toList());
        catalogue.items().forEach(item -> PanelSample.requireKey(item.id()));
        assertEquals("5 tools, 1 prompt, 1 resource, 1 resource template", catalogue.summary());
        assertNull(catalogue.absent());
    }

    @Test
    void theIdsAreTheSameOnTheNextReload() {
        assertEquals(all().items().stream().map(McpCatalogue.Item::id).toList(),
                all().items().stream().map(McpCatalogue.Item::id).toList());
    }

    @Test
    void aToolIsLabelledByItsTitleElseItsNameAndCarriesItsSchema() {
        McpCatalogue.Item titled = item(all(), "tool.current-time-2");
        McpCatalogue.Item untitled = item(all(), "tool.reset-counter");

        assertEquals("current_time", titled.target());
        assertEquals("Current time", titled.label());
        assertEquals("The current time in a zone.", titled.description());
        assertTrue(titled.schema().contains("\"zone\""), titled.schema());
        assertEquals("reset_counter", untitled.label());
        assertEquals("time://utc", item(all(), "res.860cd4a5").label());
        assertEquals("time://zone/{zone}", item(all(), "tpl.72bba68d").label());
        assertNull(item(all(), "res.860cd4a5").schema(), "a fixed resource takes no argument");
    }

    @Test
    void aToolAsksForConfirmationUnlessItIsReadOnlyAndNotDestructive() {
        assertNull(McpCatalogue.confirmation(InspectorFixtures.tool("currentTime")), "read-only");
        assertEquals("Call reset_counter? It runs the application's code, and may change data.",
                McpCatalogue.confirmation(InspectorFixtures.tool("resetCounter")), "no annotation");
        assertEquals("Call a_tool_whose_name_is_far_longer_than_forty_characters? It runs the application's code, "
                + "and may change data.", McpCatalogue.confirmation(InspectorFixtures.tool("longName")),
                "not destructive, but not read-only either");
        McpToolDescriptor plain = InspectorFixtures.tool("currentTime");
        McpToolDescriptor destructive = new McpToolDescriptor(plain.getName(), plain.getDescription(),
                plain.getInputSchema(), plain.getModernInputSchema(), plain.getBeanType(), plain.getMethod(), null,
                null, new McpToolAnnotationsModel(null, Boolean.TRUE, Boolean.TRUE, null, null));
        assertEquals("Call current_time? It runs the application's code, and may change data.",
                McpCatalogue.confirmation(destructive), "read-only, yet destructive");
        assertNull(item(all(), "prompt.plan-meeting").confirmation(), "a prompt never asks");
        assertNull(item(all(), "tpl.72bba68d").confirmation(), "a resource never asks");
    }

    @Test
    void aPromptSchemaHasAStringPerArgumentRequiredAsDeclared() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"zones\":{\"type\":\"string\","
                + "\"description\":\"Comma-separated zones\"},\"note\":{\"type\":\"string\"}},"
                + "\"required\":[\"zones\"]}", item(all(), "prompt.plan-meeting").schema());
    }

    @Test
    void aTemplateSchemaHasARequiredStringPerVariable() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"zone\":{\"type\":\"string\"}},"
                + "\"required\":[\"zone\"]}", item(all(), "tpl.72bba68d").schema());
    }

    @Test
    void readTakesTheRegistriesThatExistAndCreatesNone() {
        try (VaubanContainer container = McpTestContainers.container(InspectorFixtures.CATALOGUE_BEANS)) {
            BeanManager beans = container.getBeanManager();
            Bean<?> tools = beans.resolve(beans.getBeans(McpToolRegistry.class));

            McpCatalogue before = McpCatalogue.read(beans);

            assertEquals(McpCatalogue.NO_REGISTRY, before.absent());
            assertEquals(List.of(), before.items());
            assertNull(beans.getContext(tools.getScope()).get(tools), "read() created the tool registry");

            InspectorFixtures.fill(beans);
            McpCatalogue after = McpCatalogue.read(beans);

            assertNull(after.absent());
            assertEquals(8, after.items().size());
        }
    }

    @Test
    void noRegistryBeanMeansNoMcpServer() {
        try (VaubanContainer container = McpTestContainers.container()) {
            assertEquals(McpLiveBeans.NOT_DEPLOYED, McpCatalogue.read(container.getBeanManager()).absent());
        }
    }

    @Test
    void aToolCarriesTheArgumentsItMirrorsIntoHeaders() {
        assertEquals(Map.of(), item(all(), "tool.search").headerDesignations());
    }
}
