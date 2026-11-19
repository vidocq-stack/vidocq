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

import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The extension's identity, and the report section it writes before it read any container. */
class McpExtensionTest {

    @Test
    void itIsTheMcpContributorStartedAfterCassini() {
        McpExtension extension = new McpExtension();

        assertEquals("langchain4j-cdi-mcp", extension.name());
        assertEquals(600, extension.priority());
        StartupReportContributor contributor = assertInstanceOf(StartupReportContributor.class, extension);
        assertEquals("mcp", contributor.id());
        assertEquals("MCP server (langchain4j-cdi)", contributor.title());
    }

    @Test
    void withoutTheServerBeansTheSectionSaysNotDeployed() {
        RecordingSection section = new RecordingSection();

        new McpExtension().contribute(new FakeReportContext(Verbosity.SUMMARY), section);

        assertEquals("not deployed", section.summary);
        assertEquals(List.of(McpStartupSection.CODE_NOT_DEPLOYED), section.codes());
    }
}
