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

import io.vidocq.runtime.core.report.Section.Cells;
import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Row;
import io.vidocq.runtime.core.report.Section.Text;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text of the startup report, from models built here rather than by a boot: the two samples of the
 * design (a packaged prod boot in {@code summary}, an IDE dev boot in {@code detailed}) for the sections this
 * runtime writes, then the rules that keep a value from breaking the layout or the log.
 */
class StartupReportRendererTest {

    private static final long MS = 1_000_000;

    // ------------------------------------------------------------------------------------------ samples

    @Test
    void theSummaryIsOneLinePerSection() {
        StartupReport report = new StartupReport(LaunchMode.PROD, "auto: application archives are jars",
                Verbosity.SUMMARY, List.of(new Phase("configure", 9 * MS)),
                List.of(new Section("layer", "4 modules (vidocq.app.path=dist/app)", "4 modules (vidocq.app.path=.../app)",
                                List.of(new Cells(List.of("com.acme.app", "dist/app/app.jar", "jar"))), -1),
                        new Section("configuration", null, null, List.of(new Items("sources", List.of("SystemProperties 400"))), -1),
                        new Section("extensions", null, "chappe-engine, rest-cassini, chappe-mount-config, chappe-bootstrap",
                                List.of(new Cells(List.of("100", "chappe-engine", "onStart 0 ms"))), -1)),
                List.of(), null, null);

        assertEquals("""
                Vidocq startup report
                  launch      prod (auto: application archives are jars) | report summary | details -Dvidocq.startup.report=detailed
                  layer       4 modules (vidocq.app.path=.../app)
                  extensions  chappe-engine, rest-cassini, chappe-mount-config, chappe-bootstrap
                  anomalies   none""", StartupReportRenderer.render(report));
    }

    @Test
    void theDetailedReportLaysEverySectionOut() {
        StartupReport report = new StartupReport(LaunchMode.DEV,
                "auto: io.vidocq.tools.lc4jcdi.mcptimeserver from mcp-time-server/target/classes, pom.xml 2 levels up;"
                        + " IntelliJ agent",
                Verbosity.DETAILED,
                List.of(new Phase("configure", 9 * MS), new Phase("weaving", 4 * MS), new Phase("scan", 6 * MS),
                        new Phase("build", 71 * MS + 999_999), new Phase("extensions", 38 * MS), new Phase("report", MS)),
                List.of(new Section("layer", "4 modules (boot-layer detection from io.vidocq.tools.lc4jcdi.mcptimeserver)",
                                "4 modules (boot-layer detection from io.vidocq.tools.lc4jcdi.mcptimeserver)", List.of(
                                new Cells(List.of("io.vidocq.tools.lc4jcdi.mcptimeserver", "mcp-time-server/target/classes",
                                        "directory")),
                                new Cells(List.of("dev.langchain4j.cdi.mcp.server",
                                        "~/.m2/.../langchain4j-cdi-mcp-server-1.4.0-SNAPSHOT.jar", "jar")),
                                new Cells(List.of("dev.langchain4j.cdi.mcp.invoker.cdi41",
                                        "~/.m2/.../langchain4j-cdi-mcp-invoker-cdi41-...jar", "jar")),
                                new Cells(List.of("io.vidocq.tools.lc4jcdi.mcp.report", "~/.m2/.../lc4jcdi-mcp-report-...jar",
                                        "jar")),
                                new Row("weaving", "Vauban class loader, 1 planned bean: McpToolDiscovery")), -1),
                        new Section("configuration", null, null, List.of(
                                new Row("sources", "names and ordinals in lookup order, no value"),
                                new Items("audited", List.of("vidocq.chappe.*", "vidocq.http.*", "vidocq.launch.*",
                                        "vidocq.startup.*"))), -1),
                        new Section("extensions", null, "chappe-engine, rest-cassini, chappe-mount-config, chappe-bootstrap",
                                List.of(new Cells(List.of("100", "chappe-engine", "onStart 0 ms")),
                                        new Cells(List.of("500", "rest-cassini", "onStart 21 ms")),
                                        new Cells(List.of("7000", "chappe-mount-config", "onStart 0 ms")),
                                        new Cells(List.of("10000", "chappe-bootstrap", "onStart 15 ms"))), -1),
                        new Section("mcp", "MCP server (langchain4j-cdi, optional adapter)",
                                "2 tools, 1 prompts, 0 resources, 1 resource templates at http://localhost:8081/mcp", List.of(
                                new Text("2 tools, 1 prompts, 0 resources, 1 resource templates at http://localhost:8081/mcp"),
                                new Row("protocols", "2025-03-26 (legacy), 2026-07-28 (modern)"),
                                new Items("tools", List.of("convert_time", "current_time")),
                                new Items("prompts", List.of("plan_meeting")),
                                new Items("resource templates", List.of("time://zone/{zone}")),
                                new Row("invokers", "McpInvokerProvider present (invokers are looked up on the first call)"),
                                new Row("mrtr", "REPLAY, request state TTL PT10M"),
                                new Row("requestStateSecret", "not configured"),
                                new Row("server", "langchain4j-cdi unknown")), 3 * MS)),
                List.of(), null, "0.4.0-SNAPSHOT on Java 25");

        // The design's sample, with ASCII separators, the kind column of its first layer row one column
        // further, where the widest archive puts the column of the three other rows, and the time the mcp
        // contributor took after its title.
        assertEquals("""
                Vidocq startup report
                  launch      dev (auto: io.vidocq.tools.lc4jcdi.mcptimeserver from mcp-time-server/target/classes,
                              pom.xml 2 levels up; IntelliJ agent) | report detailed | override -Dvidocq.launch.mode=prod
                  vidocq      0.4.0-SNAPSHOT on Java 25
                  phases      configure 9 ms | weaving 4 ms | scan 6 ms | build 71 ms | extensions 38 ms | report 1 ms
                layer         4 modules (boot-layer detection from io.vidocq.tools.lc4jcdi.mcptimeserver)
                  io.vidocq.tools.lc4jcdi.mcptimeserver  mcp-time-server/target/classes                           directory
                  dev.langchain4j.cdi.mcp.server         ~/.m2/.../langchain4j-cdi-mcp-server-1.4.0-SNAPSHOT.jar  jar
                  dev.langchain4j.cdi.mcp.invoker.cdi41  ~/.m2/.../langchain4j-cdi-mcp-invoker-cdi41-...jar       jar
                  io.vidocq.tools.lc4jcdi.mcp.report     ~/.m2/.../lc4jcdi-mcp-report-...jar                      jar
                  weaving     Vauban class loader, 1 planned bean: McpToolDiscovery
                configuration
                  sources     names and ordinals in lookup order, no value
                  audited     vidocq.chappe.*, vidocq.http.*, vidocq.launch.*, vidocq.startup.*
                extensions
                  100    chappe-engine        onStart 0 ms
                  500    rest-cassini         onStart 21 ms
                  7000   chappe-mount-config  onStart 0 ms
                  10000  chappe-bootstrap     onStart 15 ms
                mcp           MCP server (langchain4j-cdi, optional adapter) | 3 ms
                  2 tools, 1 prompts, 0 resources, 1 resource templates at http://localhost:8081/mcp
                  protocols           2025-03-26 (legacy), 2026-07-28 (modern)
                  tools               convert_time, current_time
                  prompts             plan_meeting
                  resource templates  time://zone/{zone}
                  invokers            McpInvokerProvider present (invokers are looked up on the first call)
                  mrtr                REPLAY, request state TTL PT10M
                  requestStateSecret  not configured
                  server              langchain4j-cdi unknown
                anomalies     none""", StartupReportRenderer.render(report));
    }

    @Test
    void aWrappedHeaderLineNeverStartsWithASeparator() {
        List<Phase> phases = List.of(new Phase("configure", 9 * MS), new Phase("weaving", 4 * MS),
                new Phase("scan", 6 * MS), new Phase("beforeStart", 0), new Phase("build", 71 * MS),
                new Phase("extensions", 38 * MS), new Phase("audit", 0), new Phase("report", MS));
        StartupReport report = new StartupReport(LaunchMode.PROD, "vidocq.launch.mode", Verbosity.DETAILED, phases,
                List.of(), List.of(), null, null);

        List<String> lines = StartupReportRenderer.render(report).lines().toList();

        assertEquals(List.of(
                "  phases      configure 9 ms | weaving 4 ms | scan 6 ms | beforeStart 0 ms | build 71 ms |",
                "              extensions 38 ms | audit 0 ms | report 1 ms"), lines.subList(2, 4));
    }

    @Test
    void offRendersNothing() {
        assertEquals("", StartupReportRenderer.render(report(Verbosity.OFF, List.of())));
    }

    // ---------------------------------------------------------------------------------------- anomalies

    @Test
    void theSummaryRecallsTheCodesOfTheAnomalies() {
        StartupReport report = report(Verbosity.SUMMARY, List.of(),
                new Anomaly("VIDOCQ-CFG-003", "Configuration key 'vidocq.startup.reprot' is read by nothing.", null, "core"),
                new Anomaly("VIDOCQ-CFG-003", "Configuration key 'vidocq.http.prot' is read by nothing.", null, "core"),
                new Anomaly("VAUBAN-009", "Load-time weaving could not be installed", "Build with Maven", "core"));

        assertEquals("  anomalies   3: VIDOCQ-CFG-003 x2, VAUBAN-009 (logged above)",
                StartupReportRenderer.render(report).lines().toList().getLast());
    }

    @Test
    void theDetailedReportGivesEachAnomalyItsFirstSentenceOnOneLine() {
        StartupReport report = report(Verbosity.DETAILED, List.of(),
                new Anomaly("VIDOCQ-CFG-003", "Configuration key 'vidocq.startup.reprot' is read by nothing and has no"
                        + " effect. Known keys in this namespace: vidocq.startup.report", null, "core"),
                new Anomaly("VAUBAN-009", "Load-time weaving could not be installed (attach refused by this JVM, which"
                        + " was started with -XX:+DisableAttachMechanism). Either build with Maven", null, "core"));

        List<String> lines = StartupReportRenderer.render(report).lines().toList();

        assertEquals(List.of(
                "anomalies     2 (logged above)",
                "  VIDOCQ-CFG-003  Configuration key 'vidocq.startup.reprot' is read by nothing and has no effect.",
                "  VAUBAN-009      Load-time weaving could not be installed (attach refused by this JVM, which was star..."),
                lines.subList(lines.size() - 3, lines.size()));
        assertTrue(lines.stream().allMatch(line -> line.length() <= StartupReportRenderer.WIDTH), lines.toString());
    }

    @Test
    void theDetailedAnomaliesStopAtFifty() {
        Anomaly[] anomalies = IntStream.range(0, 60)
                .mapToObj(i -> new Anomaly("VIDOCQ-CFG-003", "Configuration key 'vidocq.startup.k" + i
                        + "' is read by nothing and has no effect.", null, "core"))
                .toArray(Anomaly[]::new);

        List<String> lines = StartupReportRenderer.render(report(Verbosity.DETAILED, List.of(), anomalies))
                .lines().toList();

        List<String> recalled = lines.subList(lines.indexOf("anomalies     60 (logged above)") + 1, lines.size());
        assertEquals(51, recalled.size(), recalled.toString());
        assertEquals("  VIDOCQ-CFG-003  Configuration key 'vidocq.startup.k49' is read by nothing and has no effect.",
                recalled.get(49));
        assertEquals("  ... and 10 more", recalled.getLast());
    }

    // ------------------------------------------------------------------------------------------- values

    @Test
    void controlCharactersNeverReachTheLog() {
        String injected = "ok\nINFO: Vidocq - Started in 1 ms\r[31mred  ‮";

        String text = StartupReportRenderer.render(report(Verbosity.DETAILED, List.of(
                new Section("x\nid", "headline", null, List.of(new Row("key\tname", injected),
                        new Items("list", List.of(injected)), new Cells(List.of(injected, "b")), new Text(injected)), -1))));

        assertTrue(text.lines().noneMatch(line -> line.startsWith("INFO")), text);
        assertTrue(text.chars().noneMatch(c -> c < 0x20 && c != '\n' || c == 0x7f || c == 0x85 || c == 0x2028
                || c == 0x202e), text);
        assertTrue(text.contains("x?id          head?line"), text);
        assertTrue(text.contains("  key?name    ok?INFO: Vidocq - Started in 1 ms??[31mred????"), text);
    }

    @Test
    void aValueIsCutAtTwoHundredCharacters() {
        String value = "v".repeat(250);

        assertEquals("v".repeat(197) + "...", StartupReportRenderer.clean(value));
        assertEquals(200, StartupReportRenderer.clean("w".repeat(199) + "😀" + "w").length());
        assertTrue(StartupReportRenderer.clean("w".repeat(196) + "😀" + "w".repeat(10)).endsWith("w..."),
                "a character is never cut in half");
        assertEquals("", StartupReportRenderer.clean(null));
    }

    @Test
    void aListStopsAtFiftyItems() {
        List<String> tools = IntStream.range(0, 60).mapToObj(i -> String.format("t%02d", i)).toList();

        String text = StartupReportRenderer.render(report(Verbosity.DETAILED, List.of(
                new Section("mcp", null, null, List.of(new Items("tools", tools)), -1))));

        List<String> all = text.lines().toList();
        List<String> lines = all.subList(all.indexOf("mcp") + 1, all.indexOf("anomalies     none"));
        String joined = String.join(" ", lines).replaceAll(" +", " ").strip();
        assertTrue(joined.startsWith("tools t00, t01,"), joined);
        assertTrue(joined.endsWith("t49, ... and 10 more"), joined);
        assertTrue(!joined.contains("t50"), joined);
        assertTrue(lines.stream().filter(l -> l.startsWith("   "))
                        .allMatch(l -> l.startsWith(" ".repeat(14)) && l.charAt(14) != ' '),
                "the list wraps in the value column: " + text);
        assertTrue(lines.stream().allMatch(l -> l.length() <= StartupReportRenderer.WIDTH), text);
    }

    @Test
    void aTableStopsAtFiftyRows() {
        List<Section.Line> rows = new ArrayList<>();
        IntStream.range(0, 55).forEach(i -> rows.add(new Cells(List.of("m" + i, "jar"))));

        List<String> lines = StartupReportRenderer.render(report(Verbosity.DETAILED, List.of(
                new Section("layer", "55 modules", null, rows, -1)))).lines().toList();

        assertTrue(lines.contains("  m49  jar"), lines.toString());
        assertTrue(!lines.contains("  m50  jar"), lines.toString());
        assertTrue(lines.contains("  ... and 5 more"), lines.toString());
    }

    @Test
    void anEmptyListPrintsNothing() {
        String text = StartupReportRenderer.render(report(Verbosity.DETAILED, List.of(
                new Section("configuration", null, null, List.of(new Items("sources", List.of()),
                        new Row("audited", "vidocq.startup.*")), -1))));

        assertTrue(text.contains("configuration\n  audited     vidocq.startup.*\n"), text);
    }

    @Test
    void aWideIdKeepsTwoSpacesBeforeItsValue() {
        String text = StartupReportRenderer.render(report(Verbosity.SUMMARY, List.of(
                new Section("langchain4j-mcp", "MCP", "2 tools", List.of(), -1))));

        assertTrue(text.contains("\n  langchain4j-mcp  2 tools\n"), text);
    }

    // ------------------------------------------------------------------------------------------ failure

    @Test
    void aFailureNamesItsPhaseItsTimeAndTheCodesAlreadyLogged() {
        StartupReport partial = new StartupReport(LaunchMode.DEV, "vidocq.launch.mode", Verbosity.SUMMARY,
                List.of(new Phase("configure", 3 * MS)), List.of(),
                List.of(new Anomaly("VIDOCQ-CFG-003", "unread", null, "core")), "onStart chappe-bootstrap", null);

        assertEquals("Vidocq startup failed in onStart chappe-bootstrap after 1234 ms"
                        + " (java.lang.IllegalStateException); anomalies already logged: VIDOCQ-CFG-003",
                StartupReportRenderer.failure(partial, 1234 * MS + 5, IllegalStateException.class.getName()));
        assertEquals("""
                Vidocq startup report (partial)
                  launch      dev (vidocq.launch.mode) | report summary | details -Dvidocq.startup.report=detailed
                  anomalies   1: VIDOCQ-CFG-003 (logged above)""", StartupReportRenderer.render(partial));
    }

    private static StartupReport report(Verbosity verbosity, List<Section> sections, Anomaly... anomalies) {
        return new StartupReport(LaunchMode.PROD, "vidocq.launch.mode", verbosity, List.of(), sections,
                List.of(anomalies), null, null);
    }
}
