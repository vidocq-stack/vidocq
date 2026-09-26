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
/**
 * The live mcp panel of the dev console and its MCP inspector, which only vidocq:dev adds (Vidocq/vidocq#143).
 */
module io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev {
    requires io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;
    requires io.vidocq.runtime.spi.devconsole;
    requires jakarta.cdi;
    requires dev.langchain4j.cdi.mcp.server;
    // McpLiveBeans reads the synthetic bean of the CDI 4.1 invoker provider, McpCdi41InvokerProvider, from
    // this module (Vidocq/vidocq#143). The runtime module requires it too, but not transitively, so it must
    // be required here again.
    requires dev.langchain4j.cdi.mcp.invoker.cdi41;
    // The MCP inspector: JSON-RPC with the JSON-P langchain4j-cdi already brings, over the JDK's HTTP client.
    requires jakarta.json;
    requires java.net.http;

    provides io.vidocq.runtime.spi.devconsole.LivePanel
            with io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev.McpLivePanel;
}
