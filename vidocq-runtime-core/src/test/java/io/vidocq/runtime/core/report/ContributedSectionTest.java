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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Row;
import io.vidocq.runtime.core.report.Section.Text;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a contributor writes, as the report prints it: its summary line alone in the summary report; its title,
 * the time it took, its summary line, then its rows, lists, secrets, listeners and routes in the detailed one.
 */
class ContributedSectionTest {

    private static final long MS = 1_000_000;
    private static final Anomaly ANOMALY = new Anomaly("ACME-001", "Something is off. The rest is logged.", "Fix it.",
            "mcp");

    @Test
    void theSummaryReportPrintsTheSummaryLineOfEachContributor() {
        assertEquals("""
                Vidocq startup report
                  launch      dev (vidocq.launch.mode) | report summary | details -Dvidocq.startup.report=detailed
                  mcp         2 tools, 1 prompt, 0 resources
                  http        1 listener
                  anomalies   1: ACME-001 (logged above)""", StartupReportRenderer.render(report(Verbosity.SUMMARY)));
    }

    @Test
    void theDetailedReportPrintsEverythingTheyWroteAndHowLongTheyTook() {
        assertEquals("""
                Vidocq startup report
                  launch      dev (vidocq.launch.mode) | report detailed | override -Dvidocq.launch.mode=prod
                mcp           MCP server (langchain4j-cdi) | 3 ms
                  2 tools, 1 prompt, 0 resources
                  protocols           2025-03-26 (legacy), 2026-07-28 (modern)
                  tools               convert_time, current_time
                  requestStateSecret  not configured
                  signingKey          configured
                rest          2 ms
                  POST  http://localhost:8081/mcp  McpEndpoint#handlePost
                  GET   http://localhost:8081/mcp  McpEndpoint#handleGet
                http          HTTP server | 0 ms
                  1 listener
                  default     http://localhost:8081/
                slow          62 ms, slow
                anomalies     1 (logged above)
                  ACME-001    Something is off.""", StartupReportRenderer.render(report(Verbosity.DETAILED)));
    }

    @Test
    void theLastSummaryWinsAndEmptyListsPrintNothing() {
        ContributedSection section = new ContributedSection("acme", new StartupRecorder());
        section.summary("first").summary("second").list("none", null).list("empty", List.of())
                .list("nulls", Arrays.asList("a", null)).row("value", null);

        Section written = section.toSection("Acme", -1, Map.of());

        assertEquals("second", written.summary());
        assertEquals(List.of(new Text("second"), new Items("nulls", List.of("a", "null")), new Row("value", "null")),
                written.lines());
    }

    @Test
    void aListIsCopiedWhenItIsWritten() {
        List<String> tools = new ArrayList<>(List.of("convert_time"));
        ContributedSection section = new ContributedSection("mcp", new StartupRecorder());
        section.list("tools", tools);
        tools.add("added later");

        assertEquals(List.of(new Items("tools", List.of("convert_time"))),
                section.toSection(null, -1, Map.of()).lines());
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static StartupReport report(Verbosity verbosity) {
        StartupRecorder recorder = new StartupRecorder();
        ContributedSection mcp = new ContributedSection("mcp", recorder);
        mcp.summary("2 tools, 1 prompt, 0 resources")
                .row("protocols", "2025-03-26 (legacy), 2026-07-28 (modern)")
                .list("tools", List.of("convert_time", "current_time"))
                .secret("requestStateSecret", false)
                .secret("signingKey", true);
        // declared before the listener it is on, by another section
        ContributedSection rest = new ContributedSection("rest", recorder);
        rest.route("default", "POST", "/mcp", "dev.langchain4j.cdi.mcp.server.McpEndpoint#handlePost")
                .route("default", "GET", "mcp", "McpEndpoint#handleGet");
        ContributedSection http = new ContributedSection("http", recorder);
        http.summary("1 listener").listener("default", "http://localhost:8081/");
        ContributedSection slow = new ContributedSection("slow", recorder);

        Map<String, String> listeners = ContributedSection.listeners(List.of(mcp, rest, http, slow));
        List<Section> sections = List.of(
                mcp.toSection("MCP server (langchain4j-cdi)", 3 * MS + 999_999, listeners),
                rest.toSection("rest", 2 * MS, listeners),
                http.toSection("HTTP server", 0, listeners),
                slow.toSection(null, 62 * MS, listeners));
        return new StartupReport(LaunchMode.DEV, "vidocq.launch.mode", verbosity, List.of(), sections,
                List.of(ANOMALY), null, null);
    }

    @Test
    void aLinkIsARowJoinedWithItsListenerAndPointsThere() {
        ContributedSection section = new ContributedSection("openapi", new StartupRecorder());
        section.link("Swagger UI", "default", "/openapi/ui");

        List<Section.Line> lines = section.toSection(null, -1, Map.of("default", "http://localhost:8081/")).lines();

        assertEquals(List.of(new Section.Row("Swagger UI", "http://localhost:8081/openapi/ui",
                "http://localhost:8081/openapi/ui")), lines);
    }

    @Test
    void aLinkOnAnUnknownListenerIsItsPathAndPointsNowhere() {
        ContributedSection section = new ContributedSection("openapi", new StartupRecorder());
        section.link("Swagger UI", "admin", "/openapi/ui");

        assertEquals(List.of(new Section.Row("Swagger UI", "/openapi/ui", null)),
                section.toSection(null, -1, Map.of("default", "http://localhost:8081/")).lines());
    }

    @Test
    void onlyAGetRouteWithNoTemplateVariablePointsToItsUrl() {
        ContributedSection section = new ContributedSection("rest", new StartupRecorder());
        section.route("default", "GET", "/tasks", "com.acme.Tasks#list")
                .route("default", "GET", "/tasks/{id}", "com.acme.Tasks#one")
                .route("default", "POST", "/tasks", "com.acme.Tasks#create")
                .route("admin", "GET", "/stats", "com.acme.Stats#all");

        List<String> hrefs = section.toSection(null, -1, Map.of("default", "http://localhost:8081/")).lines()
                .stream().map(line -> ((Section.Cells) line).href()).toList();

        assertEquals(java.util.Arrays.asList("http://localhost:8081/tasks", null, null, null), hrefs);
    }
}
