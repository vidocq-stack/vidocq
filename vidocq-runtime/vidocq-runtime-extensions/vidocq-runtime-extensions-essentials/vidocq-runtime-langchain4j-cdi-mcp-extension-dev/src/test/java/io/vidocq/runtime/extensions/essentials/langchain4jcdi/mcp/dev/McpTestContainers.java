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

import dev.langchain4j.cdi.mcp.server.transport.McpServerConfigResolver;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

/**
 * Real Vauban containers for the tests that need one: no HTTP, no port, no classpath scan.
 *
 * <p>Copied from the runtime module's own test fixture (Vidocq/vidocq#143), simplified: {@code McpLivePanel} never
 * calls {@code McpExtension#beforeStart}, so this copy builds the container directly instead of taking an
 * {@code McpExtension} to configure it with — which would need the runtime module's base package, never exported to
 * this one.
 */
final class McpTestContainers {

    private McpTestContainers() {}

    /** A container holding langchain4j-cdi's {@link McpServerConfigResolver} and {@code extra}. */
    static VaubanContainer container(Class<?>... extra) {
        VaubanContainerBuilder builder = VaubanContainer.builder().addBeanClass(McpServerConfigResolver.class);
        for (Class<?> type : extra) {
            builder.addBeanClass(type);
        }
        return builder.build();
    }
}
