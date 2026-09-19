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

import dev.langchain4j.cdi.mcp.server.transport.McpEndpoint;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U7 of Vidocq/vidocq#94: the langchain4j-cdi jar this extension is built with is an open module that provides
 * {@code McpServerSPI}. The extension keeps the server in the boot layer, where a {@code META-INF/services} file is
 * ignored: a langchain4j-cdi upgrade that breaks either precondition fails this build instead of two MCP calls out
 * of six.
 */
class McpServerModuleTest {

    @Test
    void theMcpServerJarIsAnOpenModuleThatProvidesTheSpi() throws Exception {
        Path jar = Path.of(McpEndpoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ModuleDescriptor descriptor = ModuleFinder.of(jar).findAll().iterator().next().descriptor();

        assertEquals("dev.langchain4j.cdi.mcp.server", descriptor.name());
        assertTrue(descriptor.isOpen(), "dev.langchain4j.cdi.mcp.server must be an open module");
        assertEquals(List.of("dev.langchain4j.cdi.mcp.server.spi.CdiMcpServerSPI"),
                descriptor.provides().stream()
                        .filter(provides -> provides.service().equals("org.mcpjava.server.spi.McpServerSPI"))
                        .flatMap(provides -> provides.providers().stream())
                        .toList(),
                "dev.langchain4j.cdi.mcp.server must declare provides org.mcpjava.server.spi.McpServerSPI");
    }
}
