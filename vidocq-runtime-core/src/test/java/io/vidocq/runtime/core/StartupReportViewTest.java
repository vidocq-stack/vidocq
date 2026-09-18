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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.banner.DevConsoleFixture;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.StartupReportView;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-only view of the startup report that the extensions read through
 * {@link ExtensionContext#startupReport()}, on real boots: empty until the report is written, the report of its
 * boot after, empty again once Vidocq stops; and every row of the contributors collected while the dev console
 * is on, whatever level the report is logged at.
 */
class StartupReportViewTest {

    private static final String REPORT_KEY = "vidocq.startup.report";
    private static final String MODE_KEY = "vidocq.launch.mode";
    private static final String CONSOLE_KEY = "vidocq.devconsole.enabled";

    @TempDir
    Path dir;

    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void startFromAnUnconfiguredJvm() {
        for (String key : List.of(REPORT_KEY, MODE_KEY, CONSOLE_KEY)) {
            saved.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreTheConfiguration() {
        saved.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    void theViewIsEmptyUntilTheReportIsWrittenAndOnceVidocqStops() {
        System.setProperty(REPORT_KEY, "summary");
        List<Boolean> seen = new ArrayList<>();
        Glue glue = new Glue("glue", (context, section) -> section.summary("written"));
        glue.whileContributing = () -> seen.add(glue.report.get().isPresent());

        VidocqBootstrap bootstrap = bootstrap(glue).configure();
        assertEquals(Optional.empty(), bootstrap.extensionContext().startupReport().get(), "configured only");
        bootstrap.start();
        try {
            assertEquals(List.of(false), glue.presentInOnStart, "empty in onStart");
            assertEquals(List.of(false), seen, "and while the contributors write");
            StartupReportView view = glue.report.get().orElseThrow();
            assertSame(view, bootstrap.extensionContext().startupReport().get().orElseThrow(),
                    "one view per boot, whoever asks");
        } finally {
            bootstrap.shutdown();
        }
        assertEquals(Optional.empty(), glue.report.get(), "the supplier an extension kept is empty once stopped");
    }

    @Test
    void theViewIsTheReportOfItsBoot() {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "summary");
        List<String> tools = IntStream.range(0, 60).mapToObj(i -> "tool" + i).toList();
        Glue glue = new Glue("glue", (context, section) -> section.summary("written")
                .row("url", "jdbc:h2:mem:demo")
                .list("tools", tools)
                .secret("password", true)
                .anomaly("GLUE-001", "Glue is off.", "Turn it on."));
        glue.title = "Glue";
        Glue failing = new Glue("failing", (context, section) -> {
            throw new IllegalStateException("boom");
        });

        VidocqBootstrap bootstrap = bootstrap(glue, failing).configure().start();
        try {
            StartupReportView view = glue.report.get().orElseThrow();

            assertEquals(LaunchMode.DEV, view.launchMode());
            assertEquals("vidocq.launch.mode", view.launchReason());
            assertEquals(List.of(new ReportAnomaly("GLUE-001", "Glue is off.", "Turn it on.", "glue")),
                    view.anomalies().stream().filter(a -> a.code().equals("GLUE-001")).toList());
            assertEquals("core", view.anomalies().stream().filter(a -> a.code().equals("VIDOCQ-RPT-001"))
                    .findFirst().orElseThrow().source());

            List<String> ids = view.sections().stream().map(ReportSection::id).filter(id -> !id.equals("vidocq"))
                    .toList();
            assertEquals(List.of("launch", "phases", "layer", "configuration", "extensions", "glue"), ids,
                    "the header's lines first, then the core's sections, then the contributed ones that were written");
            assertEquals(new ReportSection("launch", "dev (vidocq.launch.mode)", "dev (vidocq.launch.mode)", List.of(
                            new ReportLine("mode", List.of("dev")),
                            new ReportLine("reason", List.of("vidocq.launch.mode")),
                            new ReportLine("report", List.of("summary")))),
                    section(view, "launch"));
            List<String> phases = section(view, "phases").lines().stream().map(ReportLine::key).toList();
            assertEquals(List.of("configure", "weaving", "scan", "beforeStart", "build", "extensions", "audit",
                    "report"), phases);
            assertTrue(section(view, "phases").lines().getFirst().values().getFirst().endsWith(" ms"));
            assertEquals(new ReportSection("glue", "Glue", "written", List.of(
                            new ReportLine(null, List.of("written")),
                            new ReportLine("url", List.of("jdbc:h2:mem:demo")),
                            new ReportLine("tools", tools),
                            new ReportLine("password", List.of("configured")))),
                    section(view, "glue"), "every item of a list, where the log prints the first 50");

            assertEquals(List.of(glue), view.contributors(), "the very instances; the one that failed has no section");
            assertSame(glue, view.contributors().getFirst());
        } finally {
            bootstrap.shutdown();
        }
    }

    @Test
    void theDetailedTextIsTheDetailedReportWhateverTheLevelItWasLoggedAt() {
        System.setProperty(MODE_KEY, "test");
        System.setProperty(REPORT_KEY, "off");
        Glue glue = new Glue("glue", (context, section) -> section.summary("written").row("url", "jdbc:h2:mem:demo"));

        try (LogRecords records = new LogRecords("io.vidocq.startup")) {
            VidocqBootstrap bootstrap = bootstrap(glue).configure().start();
            try {
                assertEquals(List.of(), records.messages("io.vidocq.startup", java.util.logging.Level.INFO),
                        "off logs nothing");
                StartupReportView view = glue.report.get().orElseThrow();
                String text = view.detailedText();

                assertTrue(text.startsWith("Vidocq startup report\n  launch      test (vidocq.launch.mode)"
                        + " | report detailed"), text);
                assertTrue(text.matches("(?s).*\nglue {10}\\d+ ms(, slow)?\n  written\n  url {9}jdbc:h2:mem:demo\n.*"),
                        text);
                assertTrue(text.endsWith("\nanomalies     none"), text);
                assertSame(text, view.detailedText(), "rendered once");
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void theContributorsWriteEveryRowWhileTheDevConsoleIsOn() throws Exception {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "summary");

        assertEquals(Verbosity.SUMMARY, verbositySeen(null), "no console on the path");
        try (URLClassLoader console = DevConsoleFixture.withConsole(dir)) {
            assertEquals(Verbosity.DETAILED, verbositySeen(console),
                    "a reload of the dev loop logs a summary, yet the console shows every row");
            System.setProperty(CONSOLE_KEY, "false");
            assertEquals(Verbosity.SUMMARY, verbositySeen(console), "a console turned off changes nothing");
            System.setProperty(CONSOLE_KEY, "true");
            System.setProperty(MODE_KEY, "prod");
            assertEquals(Verbosity.DETAILED, verbositySeen(console), "a console forced on outside dev");
        }
    }

    @Test
    void theReportIsStillLoggedAtItsOwnLevelWhileTheDevConsoleIsOn() throws Exception {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "summary");
        Glue glue = new Glue("glue", (context, section) -> section.summary("written").row("url", "jdbc:h2:mem:demo"));

        try (URLClassLoader console = DevConsoleFixture.withConsole(dir);
             LogRecords records = new LogRecords("io.vidocq.startup")) {
            VidocqBootstrap bootstrap = withContextLoader(console, () -> bootstrap(glue).configure().start());
            try {
                String logged = records.messages("io.vidocq.startup", java.util.logging.Level.INFO).getFirst();
                assertTrue(logged.endsWith("\n  glue        written\n  anomalies   none"), logged);
                assertFalse(logged.contains("jdbc:h2:mem:demo"), logged);
                StartupReportView view = glue.report.get().orElseThrow();
                assertEquals(List.of(new ReportLine(null, List.of("written")),
                        new ReportLine("url", List.of("jdbc:h2:mem:demo"))), section(view, "glue").lines());
                assertTrue(view.sections().stream().anyMatch(section -> section.id().equals("vidocq")),
                        "the runtime is read for the console too");
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** The level a contributor is given on a boot whose context class loader is {@code loader}, if any. */
    private static Verbosity verbositySeen(ClassLoader loader) {
        List<Verbosity> seen = new ArrayList<>();
        Glue glue = new Glue("glue", (context, section) -> seen.add(context.verbosity()));
        VidocqBootstrap bootstrap = loader == null ? bootstrap(glue).configure().start()
                : withContextLoader(loader, () -> bootstrap(glue).configure().start());
        bootstrap.shutdown();
        assertEquals(1, seen.size(), seen.toString());
        assertEquals(Verbosity.SUMMARY, bootstrap.startupReport().orElseThrow().verbosity(),
                "the report itself keeps its level");
        return seen.getFirst();
    }

    private static ReportSection section(StartupReportView view, String id) {
        return view.sections().stream().filter(section -> section.id().equals(id)).findFirst().orElseThrow();
    }

    private static VidocqBootstrap bootstrap(VidocqExtension... extensions) {
        return VidocqBootstrap.create().banner(BannerMode.OFF).extensions(List.of(extensions));
    }

    /** Runs {@code boot} with {@code loader} as the context class loader, where the dev console is looked for. */
    private static VidocqBootstrap withContextLoader(ClassLoader loader, Supplier<VidocqBootstrap> boot) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return boot.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** An extension that is a contributor and keeps the report supplier its {@code onStart} is given. */
    private static final class Glue implements VidocqExtension, StartupReportContributor {
        private final String id;
        private final BiConsumer<StartupReportContext, StartupReportSection> body;
        private String title;
        private Runnable whileContributing = () -> {};
        private Supplier<Optional<StartupReportView>> report = Optional::empty;
        private final List<Boolean> presentInOnStart = new ArrayList<>();

        Glue(String id, BiConsumer<StartupReportContext, StartupReportSection> body) {
            this.id = id;
            this.body = body;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public Set<String> configKeys() {
            return Set.of();
        }

        @Override
        public void onStart(ExtensionContext context) {
            report = context.startupReport();
            presentInOnStart.add(report.get().isPresent());
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title == null ? id : title;
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            whileContributing.run();
            body.accept(context, section);
        }
    }
}
