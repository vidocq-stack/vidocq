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
package io.vidocq.runtime.core.banner;

import io.vidocq.runtime.core.BannerMode;
import io.vidocq.runtime.core.banner.BannerTestSupport.Out;
import io.vidocq.runtime.core.banner.BannerTestSupport.Records;
import io.vidocq.runtime.core.banner.StartupBanner.Launch;
import io.vidocq.runtime.core.banner.StartupBanner.Output;
import io.vidocq.runtime.core.console.ConsoleSupport;
import io.vidocq.runtime.spi.report.LaunchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static io.vidocq.runtime.core.banner.BannerTestSupport.ART_FIRST_LINE;
import static io.vidocq.runtime.core.banner.BannerTestSupport.ART_LINES;
import static io.vidocq.runtime.core.banner.BannerTestSupport.ESC;
import static io.vidocq.runtime.core.banner.BannerTestSupport.IDEA_AGENT;
import static io.vidocq.runtime.core.banner.BannerTestSupport.config;
import static io.vidocq.runtime.core.banner.BannerTestSupport.console;
import static io.vidocq.runtime.core.banner.BannerTestSupport.dev;
import static io.vidocq.runtime.core.banner.BannerTestSupport.devFromBuildTree;
import static io.vidocq.runtime.core.banner.BannerTestSupport.launch;
import static io.vidocq.runtime.core.banner.BannerTestSupport.mode;
import static io.vidocq.runtime.core.banner.BannerTestSupport.prodByAbsence;
import static io.vidocq.runtime.core.banner.BannerTestSupport.pipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The output decision, the colour policy, the once-per-JVM guard and invalid values. */
class StartupBannerTest {

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void forgetTheBanner() {
        StartupBanner.reset();
    }

    /** Every row of the decision table: mode, embedded deployment, test runtime, terminal, dev launch. */
    @ParameterizedTest(name = "{0} embedded={1} test={2} terminal={3} dev={4} -> {5}")
    @CsvSource({
            "OFF,     false, false, true,  true,  NONE",
            "OFF,     true,  true,  false, false, NONE",
            "CONSOLE, false, false, false, false, CONSOLE",
            "CONSOLE, true,  true,  false, false, CONSOLE",
            "LOG,     false, false, true,  true,  LOG",
            "AUTO,    true,  false, true,  true,  IDENTITY_ONLY",
            "AUTO,    false, true,  true,  true,  IDENTITY_ONLY",
            "AUTO,    false, false, true,  false, CONSOLE",
            "AUTO,    false, false, false, true,  CONSOLE",
            "AUTO,    false, false, false, false, IDENTITY_ONLY"})
    void decisionTable(BannerMode mode, boolean embedded, boolean test, boolean terminal, boolean dev, Output expected) {
        Launch launch = new Launch(null, embedded, test,
                dev ? dev("IntelliJ agent") : prodByAbsence(), null, null, null, null);

        assertEquals(expected, StartupBanner.decide(mode, launch, terminal));
    }

    @Test
    void aDevLaunchIsWhatTheLaunchModeResolverCallsDev() {
        assertTrue(launch(null, dev("dev reload loop"), null).devLaunch());
        assertFalse(launch(null, mode(LaunchMode.TEST, "JUnit on the stack"), null).devLaunch());
        assertFalse(launch(null, prodByAbsence(), null).devLaunch());
        assertFalse(launch(null).devLaunch(), "an unresolved mode is no dev launch");
        assertFalse(launch(null, devFromBuildTree("target/classes with a pom.xml above"), null).devLaunch(),
                "classes in a build tree are also what a piped CI job runs: no art without a terminal");
    }

    @Test
    void thisTestRuntimeIsDetected() {
        assertTrue(StartupBanner.testRuntime());
    }

    /** The colour policy of the console logs, applied to the banner: NO_COLOR, mode, terminal, TERM, OS, IntelliJ. */
    @ParameterizedTest(name = "NO_COLOR={0} color={1} terminal={2} TERM={3} os={4} idea={5} -> colours {6}")
    @CsvSource(nullValues = "null", value = {
            "null, null,   true,  xterm, Mac OS X,   false, true",
            "'',   null,   true,  xterm, Linux,      false, true",
            "1,    always, true,  xterm, Linux,      false, false",
            "1,    null,   true,  xterm, Linux,      false, false",
            "null, never,  true,  xterm, Linux,      false, false",
            "null, always, false, null,  Linux,      false, true",
            "null, auto,   false, null,  Linux,      false, false",
            "null, auto,   true,  dumb,  Linux,      false, false",
            "null, auto,   true,  xterm, Windows 11, false, false",
            "null, auto,   false, null,  Mac OS X,   true,  true"})
    void consoleOutputFollowsTheColourPolicy(String noColor, String color, boolean terminal, String term, String os,
                                             boolean intellij, boolean colours) {
        Map<String, String> values = new HashMap<>();
        if (color != null) {
            values.put(ConsoleSupport.COLOR_KEY, color);
        }
        ConsoleSupport console = intellij ? console(terminal, noColor, term, os, IDEA_AGENT)
                : console(terminal, noColor, term, os);
        Out out = new Out();

        StartupBanner.show(config(values), launch(BannerMode.CONSOLE), console, out.stream);

        String text = out.text();
        assertTrue(text.contains("Vidocq "), text);
        assertEquals(colours, text.contains(ESC), text);
        if (colours) {
            assertTrue(text.contains(ESC + "[36m" + ART_FIRST_LINE + ESC + "[0m\n"), "art in cyan: " + text);
            assertTrue(text.contains("\n " + ESC + "[1mVidocq "), "identity in bold: " + text);
            assertTrue(text.contains("\n " + ESC + "[2mJava "), "context faint: " + text);
        }
    }

    @Test
    void consoleModePrintsTheArtAndTwoLinesInOnePrint() {
        Out out = new Out();
        int[] prints = {0};
        java.io.PrintStream counting = new java.io.PrintStream(out.stream, true) {
            @Override
            public void print(String s) {
                prints[0]++;
                super.print(s);
            }
        };

        StartupBanner.show(config(Map.of()), launch(BannerMode.CONSOLE), pipe(), counting);

        assertEquals(1, prints[0]);
        List<String> lines = out.text().lines().toList();
        assertEquals(ART_LINES + 2, lines.size(), out.text());
        assertEquals(ART_FIRST_LINE, lines.getFirst());
        assertTrue(lines.get(ART_LINES).startsWith(" Vidocq "), lines.get(ART_LINES));
        assertTrue(lines.get(ART_LINES + 1).startsWith(" Java " + Runtime.version()), lines.get(ART_LINES + 1));
    }

    @Test
    void logModeIsOneRecordWhoseArtStartsInColumnZeroWithoutColours() {
        Out out = new Out();
        try (Records records = new Records()) {
            StartupBanner.show(config(Map.of(ConsoleSupport.COLOR_KEY, "always")), launch(BannerMode.LOG),
                    console(true, null, "xterm", "Linux"), out.stream);

            assertEquals("", out.text());
            List<String> identity = records.identityRecords();
            assertEquals(1, identity.size(), identity.toString());
            assertTrue(identity.getFirst().startsWith("\n" + ART_FIRST_LINE + "\n"), identity.getFirst());
            assertTrue(identity.getFirst().contains("\n Vidocq "), identity.getFirst());
            assertFalse(identity.getFirst().contains(ESC));
            assertFalse(identity.getFirst().endsWith("\n"));
        }
    }

    @Test
    void identityOnlyIsOneInfoLine() {
        Out out = new Out();
        try (Records records = new Records()) {
            StartupBanner.show(config(Map.of()), launch(null), pipe(), out.stream);

            assertEquals("", out.text());
            List<String> identity = records.identityRecords();
            assertEquals(1, identity.size(), identity.toString());
            assertTrue(identity.getFirst().matches("Vidocq .+ \\| Java .+"), identity.getFirst());
            assertFalse(identity.getFirst().contains("\n"));
            assertTrue(StartupBanner.emittedIdentity().isPresent());
        }
    }

    @Test
    void theLaunchModeAndTheDebuggerAreOnTheContextLineOfBothOutputs() {
        Out out = new Out();

        StartupBanner.show(config(Map.of()), launch(BannerMode.CONSOLE, dev("IntelliJ agent"),
                new DebugAgent("*:18095", false)), pipe(), out.stream);

        String context = out.text().lines().toList().get(ART_LINES + 1);
        assertTrue(context.contains(" | dev (IntelliJ agent) | debug *:18095"), context);
        try (Records records = new Records()) {
            StartupBanner.show(config(Map.of()), launch(null, mode(LaunchMode.PROD, "profile prod"),
                    new DebugAgent("127.0.0.1:18095", true)), pipe(), out.stream);

            String line = records.identityRecords().getFirst();
            assertTrue(line.contains(" | prod (profile prod) | debug 127.0.0.1:18095 suspend=y"), line);
        }
    }

    @Test
    void aDebuggerTheBannerCouldNotShowComesBackOnARecordOfItsOwn() throws Exception {
        Out out = new Out();
        try (Records records = new Records()) {
            StartupBanner.show(config(Map.of()), launch(BannerMode.CONSOLE, dev("IntelliJ agent"),
                    new DebugAgent("*:18095", false)), pipe(), out.stream);

            assertTrue(records.identityRecords().isEmpty(),
                    "the context line carries it: " + records.identityRecords());
        }
        Path banner = Files.writeString(dir.resolve("identity-only.txt"), "  MCP TIME SERVER ${vidocq.identity}");
        try (Records records = new Records()) {
            StartupBanner.show(config(Map.of(StartupBanner.LOCATION_KEY, banner.toString())),
                    launch(BannerMode.CONSOLE, dev("IntelliJ agent"), new DebugAgent("*:18095", true)),
                    pipe(), out.stream);

            assertEquals(List.of("Vidocq debugger: attach to *:18095, suspend=y"), records.identityRecords(),
                    "a banner that shows no context line still says how to attach");
        }
    }

    @Test
    void theDebuggerRecordRepeatsOnlyWhatWasNotShown() {
        StartupIdentity withAgent = BannerTestSupport.identity(BannerTestSupport.jar("0.4.0", null, false, null,
                null, null), null, dev("IntelliJ agent"), "debug *:5005", "app", "1.0");

        assertEquals(Optional.empty(), StartupBanner.debuggerRecord(
                launch(null, dev("IntelliJ agent"), null), withAgent, "no agent, nothing to say"));
        assertEquals(Optional.empty(), StartupBanner.debuggerRecord(
                launch(null, dev("IntelliJ agent"), new DebugAgent("*:5005", false)), withAgent,
                " Java 25 | dev (IntelliJ agent) | debug *:5005 | app 1.0\n"));
        assertEquals(Optional.of("Vidocq debugger: attach to *:5005"), StartupBanner.debuggerRecord(
                launch(null, dev("IntelliJ agent"), new DebugAgent("*:5005", false)), withAgent,
                " Java 25 | dev (IntelliJ agent) | app 1.0\n"));
        assertEquals(Optional.of("Vidocq debugger: the JDWP agent was given no address,"
                        + " see the JVM's own 'Listening for transport' line"),
                StartupBanner.debuggerRecord(launch(null, dev("IntelliJ agent"), new DebugAgent(null, false)),
                        withAgent, " Java 25 | dev (IntelliJ agent) | app 1.0\n"));
    }

    @Test
    void offPrintsAndLogsNothing() {
        Out out = new Out();
        try (Records records = new Records()) {
            StartupBanner.show(config(Map.of(StartupBanner.MODE_KEY, "console")), launch(BannerMode.OFF),
                    console(true, null, "xterm", "Linux"), out.stream);

            assertEquals("", out.text());
            assertTrue(records.messages(Level.INFO).isEmpty());
            assertTrue(StartupBanner.emittedIdentity().isEmpty());
        }
    }

    @Test
    void theProgrammaticModeWinsOverTheConfiguration() {
        Out out = new Out();

        StartupBanner.show(config(Map.of(StartupBanner.MODE_KEY, "off")), launch(BannerMode.CONSOLE), pipe(),
                out.stream);

        assertTrue(out.text().contains(" Vidocq "), out.text());
    }

    @Test
    void theConfiguredModeAppliesWithoutOverride() {
        Out out = new Out();

        StartupBanner.show(config(Map.of(StartupBanner.MODE_KEY, " Console ")), launch(null), pipe(), out.stream);

        assertTrue(out.text().startsWith(ART_FIRST_LINE + "\n"), out.text());
    }

    @Test
    void oncePerJvm() {
        Out out = new Out();
        var config = config(Map.of());

        assertTrue(StartupBanner.showOnce(config, () -> launch(BannerMode.CONSOLE), BannerTestSupport::pipe, out.stream));
        assertFalse(StartupBanner.showOnce(config, () -> launch(BannerMode.CONSOLE), BannerTestSupport::pipe, out.stream));

        assertEquals(1, out.text().lines().filter(l -> l.startsWith(" Vidocq ")).count(), out.text());
    }

    @Test
    void showOnceNeverThrows() {
        Out out = new Out();

        StartupBanner.showOnce(config(Map.of()), () -> {
            throw new IllegalStateException("boom");
        }, BannerTestSupport::pipe, out.stream);
        StartupBanner.reset();
        StartupBanner.showOnce(key -> {
            throw new NoClassDefFoundError("gone");
        }, () -> launch(null), BannerTestSupport::pipe, out.stream);

        assertEquals("", out.text());
    }

    @Test
    void anInvalidModeWarnsOnceAndBehavesAsAuto() {
        Out out = new Out();
        try (Records records = new Records()) {
            StartupBanner.show(config(Map.of(StartupBanner.MODE_KEY, "loud")), launch(null), pipe(), out.stream);

            assertEquals(List.of("Configuration key 'vidocq.banner.mode' has an unknown value 'loud'"
                    + " (expected auto, console, log or off); using auto"), records.messages(Level.WARNING));
            assertEquals("", out.text(), "auto without a terminal: no art");
            assertEquals(1, records.identityRecords().size());
        }
    }
}
