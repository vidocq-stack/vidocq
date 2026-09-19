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
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

/**
 * Produces the {@code @Named("mcp-server")} {@link McpServerConfig} that langchain4j-cdi's
 * {@code McpServerConfigResolver} reads, from what {@link McpExtension} mapped out of the {@code vidocq.mcp.*} keys.
 *
 * <ul>
 *   <li>No bean-defining annotation: the bean list {@code vidocq:generate} writes for this module never names this
 *       class, so it is a bean only when {@link McpExtension#beforeStart} adds it with
 *       {@code addSyntheticArchiveClass}, which it does only when a key is set.</li>
 *   <li>An enabled alternative: an application that also produces {@code @Named("mcp-server")} would otherwise fail
 *       the deployment ("Duplicate bean name 'mcp-server' on non-alternative beans"). Both beans exist, the keys win,
 *       and the startup report says so ({@code VIDOCQ-MCP-004}).</li>
 *   <li>{@code @Dependent}: {@code McpServerConfigResolver} caches what it gets, and no client proxy of a
 *       langchain4j-cdi type is needed.</li>
 * </ul>
 */
public class McpServerConfigProducer {

    /** Set by {@link McpExtension#beforeStart} before the container is built, cleared by {@link McpExtension#onStop}. */
    static volatile McpServerConfig config;

    /** CDI constructor. */
    public McpServerConfigProducer() {}

    /**
     * The mapped configuration.
     *
     * @return the configuration {@link McpExtension} published
     */
    @Produces
    @Named("mcp-server")
    @Alternative
    @Priority(100)
    public McpServerConfig mcpServerConfig() {
        McpServerConfig mapped = config;
        if (mapped == null) {
            throw new IllegalStateException("No vidocq.mcp.* configuration was published: McpExtension adds this "
                    + "class only when a vidocq.mcp.* key is set");
        }
        return mapped;
    }
}
