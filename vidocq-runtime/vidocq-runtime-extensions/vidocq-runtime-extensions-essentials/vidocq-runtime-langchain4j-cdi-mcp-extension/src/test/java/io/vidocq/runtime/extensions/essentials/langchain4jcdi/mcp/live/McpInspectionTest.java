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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live;

import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.FixtureApplicationConfigProducer;
import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.McpExtension;
import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.McpTestContainers;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@link McpInspection} reads from a real container: how many {@code @Named("mcp-server")} beans exist and
 * whether the runtime extension produces one. The twin case needs a second copy of langchain4j-cdi in a child
 * layer: the launch test {@code McpLaunchShapesTest} forces it.
 */
class McpInspectionTest {

    /** {@code McpConfigMapping.SERVER_NAME}, package-private in the base package: repeated here as a literal. */
    private static final String SERVER_NAME_KEY = "vidocq.mcp.serverName";

    private final McpExtension extension = new McpExtension();

    @AfterEach
    void stop() {
        extension.onStop();
    }

    @Test
    void noConfigBean() {
        extension.configure(config(Map.of()));
        try (VaubanContainer container = McpTestContainers.container(extension)) {
            assertEquals(new McpInspection(false, 0, false), McpInspection.of(container.getBeanManager()));
        }
    }

    @Test
    void theExtensionsConfigBean() {
        extension.configure(config(Map.of(SERVER_NAME_KEY, "time-server")));
        try (VaubanContainer container = McpTestContainers.container(extension)) {
            assertEquals(new McpInspection(false, 1, true), McpInspection.of(container.getBeanManager()));
        }
    }

    @Test
    void anApplicationConfigBean() {
        extension.configure(config(Map.of()));
        try (VaubanContainer container = McpTestContainers.container(extension, FixtureApplicationConfigProducer.class)) {
            assertEquals(new McpInspection(false, 1, false), McpInspection.of(container.getBeanManager()));
        }
    }

    @Test
    void bothConfigBeans() {
        extension.configure(config(Map.of(SERVER_NAME_KEY, "time-server")));
        try (VaubanContainer container = McpTestContainers.container(extension, FixtureApplicationConfigProducer.class)) {
            assertEquals(new McpInspection(false, 2, true), McpInspection.of(container.getBeanManager()));
        }
    }

    @Test
    void aContainerThatCannotBeReadReadsAsNothing() {
        assertEquals(McpInspection.NOTHING, McpInspection.of(null));
    }

    /** Same one-liner as {@code McpConfigMappingTest.config}, kept local: a different package cannot reach it. */
    private static VidocqConfiguration config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }
}
