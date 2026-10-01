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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** The contributors and panels the snapshot tests show. */
final class TestPanels {

    private TestPanels() {}

    /** A panel with boot facts, one chart and a value per pool, counting its samples. */
    static final class PoolPanel implements DevConsolePanel {

        final AtomicInteger samples = new AtomicInteger();

        @Override
        public String id() {
            return "acme-pool";
        }

        @Override
        public String title() {
            return "Acme pools";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("1 pool, 8 connections max")
                    .row("main", "jdbc:h2:mem:acme")
                    .secret("main password", true);
        }

        @Override
        public List<Chart> charts() {
            return List.of(new Chart("connections", "Connections",
                    List.of(Series.area("active"), Series.ceiling("active"))));
        }

        @Override
        public void sample(PanelSample sample) {
            samples.incrementAndGet();
            sample.group("main").gauge("active", 3, 8, Unit.COUNT);
        }
    }

    /** A panel whose samples fail with a message that holds a secret. */
    static final class BrokenPanel implements DevConsolePanel {

        @Override
        public String id() {
            return "broken";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("boot facts survive");
        }

        @Override
        public List<Chart> charts() {
            throw new IllegalStateException("no charts either");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.gauge("half", 1, Unit.COUNT);
            throw new IllegalStateException("password=hunter2");
        }
    }

    /** A panel whose samples fail with an error, not an exception. */
    static final class LinkagePanel implements DevConsolePanel {

        @Override
        public String id() {
            return "linkage";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("a class is missing");
        }

        @Override
        public void sample(PanelSample sample) {
            throw new NoClassDefFoundError("com/acme/Missing");
        }
    }

    /** A contributor that is no panel: boot facts only. */
    static final class FactsOnly implements StartupReportContributor {

        @Override
        public String id() {
            return "facts";
        }

        @Override
        public String title() {
            return "Facts only";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("2 things").list("things", List.of("a", "b"));
        }
    }

    /** A contributor that is no panel, for a section a live panel may make live. */
    static final class Static implements StartupReportContributor {

        private final String id;

        Static(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("static");
        }
    }

    /**
     * A panel with actions: {@code clear}, which takes nothing; {@code set-level}, which takes a logger and a level;
     * {@code fail}, which throws with a secret in its message; {@code slow}, which waits for {@link #release}.
     * Counts the calls of {@code actions()} and records the arguments of every run.
     */
    static final class ActionPanel implements DevConsolePanel {

        final AtomicInteger actionsCalls = new AtomicInteger();
        final List<Map<String, String>> runs = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String id() {
            return "acme-actions";
        }

        @Override
        public String title() {
            return "Acme actions";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("things to do");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.counter("runs", runs.size(), Unit.COUNT);
        }

        @Override
        public List<PanelAction> actions() {
            actionsCalls.incrementAndGet();
            return List.of(
                    new PanelAction("clear", "Clear the cache", null, arguments -> {
                        runs.add(arguments);
                        return "0 entries";
                    }),
                    new PanelAction("set-level", "Set level", "Change the level of this logger?", List.of(
                            PanelAction.Argument.matching("logger", "Logger", "[A-Za-z0-9_.$]{1,120}"),
                            PanelAction.Argument.oneOf("level", "Level", "INFO", "DEBUG")), arguments -> {
                                runs.add(arguments);
                                return arguments.get("logger") + " at " + arguments.get("level");
                            }),
                    new PanelAction("fail", "Fail", null, arguments -> {
                        runs.add(arguments);
                        throw new IllegalStateException("password=hunter2");
                    }),
                    new PanelAction("slow", "Slow", null, arguments -> {
                        runs.add(arguments);
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return "finally";
                    }));
        }
    }

    /** A panel whose actions take a json argument and return structured results, as the MCP inspector's do. */
    static final class InspectorPanel implements DevConsolePanel {

        static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                + "\"required\":[\"city\"]}";

        final List<Map<String, String>> runs = new CopyOnWriteArrayList<>();

        @Override
        public String id() {
            return "acme-inspect";
        }

        @Override
        public String title() {
            return "Acme inspector";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("things to call");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.counter("runs", runs.size(), Unit.COUNT);
        }

        @Override
        public List<PanelAction> actions() {
            return List.of(
                    new PanelAction("tool.weather", "Weather", null,
                            List.of(PanelAction.Argument.json("arguments", "Arguments", SCHEMA)), arguments -> {
                                runs.add(arguments);
                                return new PanelAction.ActionResult("ok in 3 ms", "application/json",
                                        "{\"temp\":21}", false, "{\"request\":{\"method\":\"tools/call\"}}");
                            }, "Tools", "The weather in a city.\nIn Celsius."),
                    new PanelAction("tool.broken", "Broken", null,
                            List.of(PanelAction.Argument.json("arguments", "Arguments", "{\"type\":\"object\"}")),
                            arguments -> {
                                runs.add(arguments);
                                return new PanelAction.ActionResult("error -32602: Invalid params",
                                        "application/json", "{\"code\":-32602}", true, null);
                            }, "Tools", null),
                    new PanelAction("note", "Note", null,
                            List.of(PanelAction.Argument.matching("text", "Text", "[a-z]{1,200}")), arguments -> {
                                runs.add(arguments);
                                return "noted";
                            }));
        }
    }

    /**
     * A panel with a query action and the languages {@link #languages} holds, {@code jdql} by default; counts the
     * calls of {@code languages()}, and throws from it when {@link #failure} is set.
     */
    static final class LanguagePanel implements DevConsolePanel {

        static final String JDQL = "{\"mode\":\"query\",\"targets\":{\"Task\":{\"detail\":\"table task\","
                + "\"attributes\":{\"title\":{\"type\":\"string\",\"detail\":\"String · column title\"}}}}}";

        final AtomicInteger languagesCalls = new AtomicInteger();
        List<PanelLanguage> languages = List.of(new PanelLanguage("jdql", JDQL));
        RuntimeException failure;

        @Override
        public String id() {
            return "acme-data";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("things to query");
        }

        @Override
        public void sample(PanelSample sample) {
            // no value
        }

        @Override
        public List<PanelAction> actions() {
            return List.of(new PanelAction("query", "Query", null, List.of(PanelAction.Argument.json("statement",
                    "Statement", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"format\":"
                            + "\"textarea\",\"contentMediaType\":\"text/x-query\",\"x-language\":\"jdql\"}}}")),
                    arguments -> "0 rows"));
        }

        @Override
        public List<PanelLanguage> languages() {
            languagesCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return languages;
        }
    }

    /** A panel offering more actions than the console keeps. */
    static final class ManyActionsPanel implements DevConsolePanel {

        @Override
        public String id() {
            return "acme-many";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("many things to do");
        }

        @Override
        public void sample(PanelSample sample) {
            // no value
        }

        @Override
        public List<PanelAction> actions() {
            return IntStream.range(0, 130)
                    .mapToObj(i -> new PanelAction("tool.t" + i, "Tool " + i, null, arguments -> "ok"))
                    .toList();
        }
    }
}
