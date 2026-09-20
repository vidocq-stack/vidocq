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
 * Hosts a langchain4j-cdi MCP server on Vidocq: the server's bean list, the {@code vidocq.mcp.*} keys, the
 * {@code mcp} section of the startup report, and that section shown live as the dev console's {@code mcp} panel.
 */
module io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp {
    requires transitive io.vidocq.runtime.spi;
    // McpExtension is its own dev console panel: its report section, shown live. Hard, not static: a class does
    // not load without the interfaces it implements. Transitive, as io.vidocq.runtime.spi above, so that a module
    // reading this one sees the SPI McpExtension is typed with; the module is small and brings only that SPI.
    requires transitive io.vidocq.runtime.spi.devconsole;
    requires io.vidocq.vauban.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    // @Priority enables McpServerConfigProducer's alternative.
    requires jakarta.annotation;
    // Typed on purpose: a module kept in the boot layer keeps what it reads, so the MCP server stays in the boot
    // layer in every launch shape (Vidocq/vidocq#94, option A).
    requires dev.langchain4j.cdi.mcp.server;
    // Not static: the CDI 4.1 invoker is resolved in every launch and stays in the boot layer with the server.
    requires dev.langchain4j.cdi.mcp.invoker.cdi41;

    // Vauban creates McpServerConfigProducer and calls its producer method reflectively: this module has no APT.
    opens io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.McpExtension;
}
