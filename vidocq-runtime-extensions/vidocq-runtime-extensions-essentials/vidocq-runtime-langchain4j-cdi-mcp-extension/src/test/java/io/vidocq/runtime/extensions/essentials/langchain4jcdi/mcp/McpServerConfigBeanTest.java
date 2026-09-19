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

import dev.langchain4j.cdi.mcp.server.transport.McpServerConfig;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfigResolver;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.literal.NamedLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * U2 of Vidocq/vidocq#94, in a real Vauban container: the {@code @Named("mcp-server")} bean exists only when a
 * {@code vidocq.mcp.*} key is set, it is what langchain4j-cdi's resolver serves, and it wins over an application
 * producer of the same name instead of failing the deployment.
 */
class McpServerConfigBeanTest {

    private final McpExtension extension = new McpExtension();

    @AfterEach
    void stop() {
        extension.onStop();
    }

    @Test
    void noKeyAddsNoBeanAndTheServerKeepsItsDefaults() {
        extension.configure(McpConfigMappingTest.config(Map.of()));

        try (VaubanContainer container = McpTestContainers.container(extension)) {
            assertEquals(0, namedConfigBeans(container));
            assertEquals("langchain4j-cdi", served(container).getServerName());
        }
    }

    @Test
    void aKeyPublishesTheMappedConfigurationToTheServer() {
        extension.configure(McpConfigMappingTest.config(Map.of(
                McpConfigMapping.SERVER_NAME, "time-server", McpConfigMapping.SERVER_VERSION, "1.2.3")));

        try (VaubanContainer container = McpTestContainers.container(extension)) {
            assertEquals(1, namedConfigBeans(container));
            assertEquals("time-server", served(container).getServerName());
            assertEquals("1.2.3", served(container).getServerVersion());
        }
    }

    @Test
    void anApplicationProducerAloneIsServed() {
        extension.configure(McpConfigMappingTest.config(Map.of()));

        try (VaubanContainer container = McpTestContainers.container(extension, FixtureApplicationConfigProducer.class)) {
            assertEquals(1, namedConfigBeans(container));
            assertEquals("application-server", served(container).getServerName());
        }
    }

    @Test
    void theKeysWinOverAnApplicationProducer() {
        extension.configure(McpConfigMappingTest.config(Map.of(McpConfigMapping.SERVER_NAME, "time-server")));

        try (VaubanContainer container = McpTestContainers.container(extension, FixtureApplicationConfigProducer.class)) {
            assertEquals(2, namedConfigBeans(container), "both beans exist: VIDOCQ-MCP-004 counts them");
            assertEquals("time-server", served(container).getServerName());
        }
    }

    @Test
    void onStopForgetsThePublishedConfiguration() {
        extension.configure(McpConfigMappingTest.config(Map.of(McpConfigMapping.SERVER_NAME, "time-server")));
        try (VaubanContainer ignored = McpTestContainers.container(extension)) {
            extension.onStop();
            assertNull(McpServerConfigProducer.config);
        }
    }

    private static int namedConfigBeans(VaubanContainer container) {
        return container.getBeanManager().getBeans(McpServerConfig.class, NamedLiteral.of("mcp-server")).size();
    }

    private static McpServerConfig served(VaubanContainer container) {
        return container.select(McpServerConfigResolver.class).get();
    }
}
