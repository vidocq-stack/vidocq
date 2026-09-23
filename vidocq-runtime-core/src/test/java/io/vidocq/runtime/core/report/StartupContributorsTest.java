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

import io.vidocq.runtime.core.report.StartupContributors.Contributor;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup report contributors, found and called without a boot: extensions first, then the services of
 * the {@link ServiceLoader}, each class once whatever layer loaded it, each id once; a contributor that fails
 * loses its section and nothing else.
 */
class StartupContributorsTest {

    private static final String RPT_001 = "VIDOCQ-RPT-001";
    private static final String RPT_002 = "VIDOCQ-RPT-002";

    @TempDir
    Path dir;

    private final StartupRecorder recorder = new StartupRecorder();
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            synchronized (warnings) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    @BeforeEach
    void listenToTheAnomalies() {
        handler.setLevel(Level.ALL);
        Logger.getLogger(StartupAnomalies.LOGGER_NAME).addHandler(handler);
    }

    @AfterEach
    void stopListening() {
        Logger.getLogger(StartupAnomalies.LOGGER_NAME).removeHandler(handler);
    }

    // ---------------------------------------------------------------------------------------- discovery

    @Test
    void extensionsComeFirstInTheirOrderThenTheServicesByOrderThenId() throws IOException {
        ContributorFixtures.onClassPath(dir, "fixture.order.Zeta", "zeta", 1000, "zeta");
        ContributorFixtures.onClassPath(dir, "fixture.order.Alpha", "alpha", 1000, "alpha");
        ContributorFixtures.onClassPath(dir, "fixture.order.Early", "early", 10, "early");
        List<VidocqExtension> extensions = List.of(new Contributing("second-by-priority"), new Plain(),
                new Contributing("third-by-priority") {});

        try (URLClassLoader loader = ContributorFixtures.classPathLoader(dir)) {
            List<Contributor> found = StartupContributors.discover(extensions, loader, recorder);

            assertEquals(List.of("second-by-priority", "third-by-priority", "early", "alpha", "zeta"), ids(found));
            assertEquals("fixture.order.Early", found.get(2).className());
            assertEquals(List.of(), recorder.anomalies());
        }
    }

    @Test
    void layerTwinsAreKeptOnceAndTheSecondCopyIsNeverCreated() throws IOException {
        ClassLoader child = ContributorFixtures.twinLayers(dir);
        Set<Class<?>> copies = ServiceLoader.load(StartupReportContributor.class, child).stream()
                .map(ServiceLoader.Provider::type)
                .filter(type -> type.getName().equals(ContributorFixtures.TWIN_CLASS))
                .collect(Collectors.toSet());
        assertEquals(2, copies.size(), "the service loader finds both copies: " + copies);

        List<Contributor> found = StartupContributors.discover(List.of(), child, recorder);

        assertEquals(List.of(ContributorFixtures.TWIN_ID), ids(found));
        assertSame(child, found.getFirst().instance().getClass().getClassLoader(), "the first copy, the child layer's");
        assertEquals(List.of(), recorder.anomalies(), "the parent layer's copy, which throws, is never created");
        List<Section> sections = StartupContributors.call(found, context(Verbosity.SUMMARY), recorder).sections();
        assertEquals(ContributorFixtures.TWIN_SUMMARY, sections.getFirst().summary());
    }

    @Test
    void aServiceThatCannotBeLoadedOrCreatedIsSkippedAndTheOthersAreFound() throws IOException {
        ContributorFixtures.listed(dir, "fixture.missing.Nowhere");
        ContributorFixtures.throwingOnClassPath(dir, "fixture.broken.Throwing");
        ContributorFixtures.onClassPath(dir, "fixture.fine.Fine", "fine", 1000, "fine");

        try (URLClassLoader loader = ContributorFixtures.classPathLoader(dir)) {
            List<Contributor> found = StartupContributors.discover(List.of(), loader, recorder);

            assertEquals(List.of("fine"), ids(found));
        }
        assertEquals(List.of(RPT_001, RPT_001), codes());
        List<LogRecord> logged = logged(RPT_001);
        assertEquals(2, logged.size(), messages().toString());
        String notLoaded = message(logged.get(0));
        assertTrue(notLoaded.startsWith("[VIDOCQ-RPT-001] A startup report contributor could not be loaded ("),
                notLoaded);
        assertTrue(notLoaded.contains("fixture.missing.Nowhere"), notLoaded);
        assertTrue(notLoaded.endsWith("); its section is skipped"), notLoaded);
        String notCreated = message(logged.get(1));
        assertTrue(notCreated.startsWith("[VIDOCQ-RPT-001] Startup report contributor fixture.broken.Throwing could"
                + " not be created ("), notCreated);
        assertNotNull(logged.get(0).getThrown(), "with its stack trace");
        assertNotNull(logged.get(1).getThrown(), "with its stack trace");
    }

    @Test
    void aContributorWithoutAnIdIsSkipped() {
        IllegalStateException boom = new IllegalStateException("no id");
        List<VidocqExtension> extensions = List.of(new Contributing(null), new Contributing("  "),
                new Contributing("unused") {
                    @Override
                    public String id() {
                        throw boom;
                    }
                }, new Contributing("kept"));

        List<Contributor> found = StartupContributors.discover(extensions, loader(), recorder);

        assertEquals(List.of("kept"), ids(found));
        assertEquals(List.of(RPT_001, RPT_001, RPT_001), codes());
        assertEquals("[VIDOCQ-RPT-001] Startup report contributor " + Contributing.class.getName()
                + " has no id; its section is skipped", messages().getFirst());
        assertSame(boom, logged(RPT_001).get(2).getThrown());
    }

    @Test
    void twoClassesWithOneIdKeepTheFirstAndTheCoreKeepsItsOwn() {
        Contributing first = new Contributing("dup");
        Contributing second = new Contributing("dup") {};
        Contributing layer = new Contributing("layer");

        List<Contributor> found = StartupContributors.discover(List.of(first, second, layer), loader(), recorder);

        assertEquals(List.of("dup"), ids(found));
        assertSame(first, found.getFirst().instance());
        assertEquals(List.of(RPT_002, RPT_002), codes());
        String secondClass = second.getClass().getName();
        assertEquals("[VIDOCQ-RPT-002] Contributors " + Contributing.class.getName() + " and " + secondClass
                + " both use id 'dup'; " + secondClass + " is skipped", messages().get(0));
        assertEquals("[VIDOCQ-RPT-002] Contributor " + Contributing.class.getName() + " uses id 'layer', which names"
                + " a section of the core; it is skipped", messages().get(1));
    }

    @Test
    void theDevConsolePanelsOfTheCoreAreReservedToo() {
        Contributing startup = new Contributing("startup");
        Contributing config = new Contributing("config") {};
        Contributing cdi = new Contributing("cdi") {};
        Contributing jvm = new Contributing("jvm") {};

        List<Contributor> found = StartupContributors.discover(List.of(startup, config, cdi, jvm), loader(),
                recorder);

        assertEquals(List.of(), ids(found),
                "the dev console shows its own 'startup', 'config', 'cdi' and 'jvm' panels");
        assertEquals(List.of(RPT_002, RPT_002, RPT_002, RPT_002), codes());
        assertEquals("[VIDOCQ-RPT-002] Contributor " + Contributing.class.getName() + " uses id 'startup', which"
                + " names a section of the core; it is skipped", messages().getFirst());
    }

    @Test
    void theSameClassTwiceIsATwinAndNotADuplicate() {
        List<Contributor> found = StartupContributors.discover(
                List.of(new Contributing("same"), new Contributing("same")), loader(), recorder);

        assertEquals(List.of("same"), ids(found));
        assertEquals(List.of(), codes());
    }

    // -------------------------------------------------------------------------------------------- calls

    @Test
    void eachContributorWritesItsOwnTimedSectionUnderItsTitle() {
        List<Section> sections = StartupContributors.call(List.of(
                        contributor("mcp", "MCP server", (context, section) -> section.summary("2 tools")
                                .row("mrtr", "REPLAY")),
                        contributor("plain", "plain", (context, section) -> section.summary("no title"))),
                context(Verbosity.DETAILED), recorder).sections();

        assertEquals(List.of("mcp", "plain"), sections.stream().map(Section::id).toList());
        Section mcp = sections.getFirst();
        assertEquals("MCP server", mcp.headline());
        assertEquals("2 tools", mcp.summary());
        assertEquals(List.of(new Section.Text("2 tools"), new Section.Row("mrtr", "REPLAY")), mcp.lines());
        assertTrue(mcp.nanos() >= 0, "timed: " + mcp.nanos());
        assertNull(sections.get(1).headline(), "a title that is the id says nothing more");
    }

    @Test
    void aFailingContributorLosesItsSectionAndKeepsItsAnomalies() {
        IllegalStateException boom = new IllegalStateException("boom");
        List<String> called = new ArrayList<>();
        Contributor after = contributor("after", "after", (context, section) -> called.add("after"));
        StartupContributors.Contributed contributed = StartupContributors.call(List.of(
                        contributor("failing", "failing", (context, section) -> {
                            section.summary("half written").anomaly("ACME-001", "Something is off.", null);
                            throw boom;
                        }),
                        contributor("linkage", "linkage", (context, section) -> {
                            throw new NoClassDefFoundError("com/acme/Gone");
                        }),
                        after),
                context(Verbosity.DETAILED), recorder);

        assertEquals(List.of("after"), contributed.sections().stream().map(Section::id).toList());
        assertEquals(List.of(after.instance()), contributed.contributors(),
                "the instances whose sections the report has, and only those");
        assertEquals(List.of("after"), called);
        assertEquals(List.of("ACME-001", RPT_001, RPT_001), codes());
        assertEquals("failing", recorder.anomalies().getFirst().source());
        LogRecord failed = logged(RPT_001).getFirst();
        assertEquals("[VIDOCQ-RPT-001] Startup report contributor 'failing' (" + Contributing.class.getName()
                + ") failed; its section is skipped", message(failed));
        assertSame(boom, failed.getThrown());
        assertInstanceOf(NoClassDefFoundError.class, logged(RPT_001).get(1).getThrown());
    }

    @Test
    void anErrorThatIsNotALinkageErrorGoesThrough() {
        AssertionError fatal = new AssertionError("fatal");
        List<Contributor> contributors = List.of(contributor("fatal", "fatal", (context, section) -> {
            throw fatal;
        }));

        assertSame(fatal, assertThrows(AssertionError.class,
                () -> StartupContributors.call(contributors, context(Verbosity.DETAILED), recorder)));
        assertEquals("contributor fatal", recorder.failing());
    }

    @Test
    void anAnomalyIsLoggedAtOnceAndCleanedEvenWhenTheReportIsOff() {
        List<String> loggedWhileContributing = new ArrayList<>();
        StartupContributors.call(List.of(contributor("acme", "acme", (context, section) -> {
            section.anomaly("ACME-001", "Broken\nline\033[31m.", "Fix it.");
            loggedWhileContributing.addAll(messages());
        })), context(Verbosity.OFF), recorder);

        assertEquals(List.of("[ACME-001] Broken?line?[31m. Fix it."), loggedWhileContributing);
        assertEquals(List.of(new Anomaly("ACME-001", "Broken?line?[31m.", "Fix it.", "acme")),
                recorder.anomalies());
    }

    @Test
    void routesAreJoinedWithTheirListenerWhicheverSectionDeclaredIt() {
        List<List<String>> seen = new ArrayList<>();
        List<Section> sections = StartupContributors.call(List.of(
                        contributor("rest", "rest", (context, section) -> section
                                .route("default", "POST", "/mcp", "com.acme.McpEndpoint#handlePost")
                                .route("admin", "GET", "health", "com.acme.Health")),
                        contributor("http", "http", (context, section) -> section
                                .listener("default", "http://localhost:8081/")),
                        contributor("probe", "probe", (context, section) -> {
                            seen.add(context.routeUrls("com.acme.McpEndpoint"));
                            seen.add(context.routeUrls("com.acme.Health"));
                            seen.add(context.routeUrls("McpEndpoint"));
                            seen.add(context.routeUrls(null));
                        })),
                context(Verbosity.DETAILED), recorder).sections();

        assertEquals(List.of(List.of("http://localhost:8081/mcp"), List.of(), List.of(), List.of()), seen);
        assertEquals(List.of(new Section.Cells(List.of("POST", "http://localhost:8081/mcp", "McpEndpoint#handlePost")),
                new Section.Cells(List.of("GET", "health", "Health"))), sections.getFirst().lines());
        assertEquals(List.of(new Section.Row("default", "http://localhost:8081/")), sections.get(1).lines());
    }

    // --------------------------------------------------------------------------------------------- context

    @Test
    void aContextWithoutAContainerAnswersWithNothingAndNeverThrows() {
        ContributorContext context = new ContributorContext(null, null, null);

        assertEquals(Verbosity.OFF, context.verbosity());
        assertEquals(LaunchMode.PROD, context.launchMode());
        assertFalse(context.hasBeanOfType("com.acme.Tools"));
        assertFalse(context.hasBeanOfType(null));
        assertEquals(Optional.empty(), context.lookup(String.class));
        assertEquals(Optional.empty(), context.lookup(null));
        assertEquals(List.of(), context.routeUrls("com.acme.Tools"));
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static ContributorContext context(Verbosity verbosity) {
        return new ContributorContext(verbosity, LaunchMode.DEV, null);
    }

    private static ClassLoader loader() {
        return StartupContributorsTest.class.getClassLoader();
    }

    private static Contributor contributor(String id, String title,
                                           BiConsumer<StartupReportContext, StartupReportSection> body) {
        return new Contributor(new Contributing(id, body), id, title, Contributing.class.getName());
    }

    private static List<String> ids(List<Contributor> contributors) {
        return contributors.stream().map(Contributor::id).toList();
    }

    private List<String> codes() {
        return recorder.anomalies().stream().map(Anomaly::code).toList();
    }

    private List<String> messages() {
        synchronized (warnings) {
            return warnings.stream().map(StartupContributorsTest::message).toList();
        }
    }

    private List<LogRecord> logged(String code) {
        synchronized (warnings) {
            return warnings.stream().filter(r -> message(r).startsWith("[" + code + "]")).toList();
        }
    }

    private static String message(LogRecord record) {
        return record.getMessage();
    }

    /** An extension that is not a contributor. */
    private static class Plain implements VidocqExtension {
        @Override
        public String name() {
            return "plain";
        }
    }

    /** An extension that is also a contributor. */
    private static class Contributing implements VidocqExtension, StartupReportContributor {
        private final String id;
        private final BiConsumer<StartupReportContext, StartupReportSection> body;

        Contributing(String id) {
            this(id, (context, section) -> section.summary("from " + id));
        }

        Contributing(String id, BiConsumer<StartupReportContext, StartupReportSection> body) {
            this.id = id;
            this.body = body;
        }

        @Override
        public String name() {
            return "contributing " + id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            body.accept(context, section);
        }
    }
}
