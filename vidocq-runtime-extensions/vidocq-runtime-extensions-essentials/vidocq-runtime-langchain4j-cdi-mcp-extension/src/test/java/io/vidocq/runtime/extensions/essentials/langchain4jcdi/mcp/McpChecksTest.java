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

import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure checks behind {@code VIDOCQ-MCP-001} and {@code VIDOCQ-MCP-005}. */
class McpChecksTest {

    private static final String SERVER = "dev.langchain4j.cdi.mcp.server";
    private static final String PROVIDER = "dev.langchain4j.cdi.mcp.server.spi.CdiMcpServerSPI";

    @Test
    void anOpenModuleThatProvidesTheSpiHasNoProblem() {
        ModuleDescriptor fine = ModuleDescriptor.newOpenModule(SERVER)
                .provides(McpChecks.SERVER_SPI, List.of(PROVIDER))
                .build();

        assertEquals(List.of(), McpChecks.descriptorProblems(fine));
    }

    @Test
    void aClosedModuleWithoutTheProviderHasBothProblems() {
        List<String> problems = McpChecks.descriptorProblems(ModuleDescriptor.newModule(SERVER).build());

        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("does not provide org.mcpjava.server.spi.McpServerSPI"), problems.get(0));
        assertTrue(problems.get(1).startsWith("is not an open module"), problems.get(1));
    }

    @Test
    void explicitNamesAndFrameworkTypesNeedNoCompilerFlag() {
        List<java.lang.reflect.Method> methods = List.of(FixtureMcpBeans.Named.class.getDeclaredMethods());

        assertEquals(List.of(), McpChecks.unnamedParameters(methods));
    }

    @Test
    void aParameterWithoutAnyNameIsReportedWithItsMethod() {
        List<java.lang.reflect.Method> methods = List.of(FixtureMcpBeans.Unnamed.class.getDeclaredMethods());

        assertEquals(List.of("Unnamed#echo", "Unnamed#greet"), McpChecks.unnamedParameters(methods));
    }
}
