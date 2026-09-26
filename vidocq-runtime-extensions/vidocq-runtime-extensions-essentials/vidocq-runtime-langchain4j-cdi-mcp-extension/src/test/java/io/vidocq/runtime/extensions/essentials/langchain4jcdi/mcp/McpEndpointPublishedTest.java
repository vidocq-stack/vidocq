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

import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpEndpointLive;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The URLs of {@code /mcp} the startup report resolved, published for the MCP inspector of the {@code -dev} module:
 * each once, without the internal listen route, and forgotten when the extension stops, so that a dev reload never
 * calls the previous boot's address.
 */
class McpEndpointPublishedTest {

    @AfterEach
    void clear() {
        McpEndpointLive.clear();
    }

    @Test
    void contributePublishesTheUrlsOfMcpAndOnStopForgetsThem() {
        McpExtension extension = new McpExtension();
        FakeReportContext context = new FakeReportContext(Verbosity.SUMMARY)
                .route(McpStartupSection.ENDPOINT, "http://127.0.0.1:18090/mcp")
                .route(McpStartupSection.ENDPOINT, "http://127.0.0.1:18090/mcp")
                .route(McpStartupSection.ENDPOINT, "http://127.0.0.1:18090/mcp/_listen");

        extension.contribute(context, new RecordingSection());

        assertEquals(List.of("http://127.0.0.1:18090/mcp"), McpEndpointLive.urls());

        extension.onStop();

        assertEquals(List.of(), McpEndpointLive.urls());
    }

    @Test
    void noRouteMeansNoUrl() {
        new McpExtension().contribute(new FakeReportContext(Verbosity.SUMMARY), new RecordingSection());

        assertEquals(List.of(), McpEndpointLive.urls());
    }
}
