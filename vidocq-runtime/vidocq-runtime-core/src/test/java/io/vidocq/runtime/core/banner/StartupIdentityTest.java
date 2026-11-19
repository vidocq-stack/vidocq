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

import io.vidocq.runtime.spi.report.LaunchMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.vidocq.runtime.core.banner.BannerTestSupport.dev;
import static io.vidocq.runtime.core.banner.BannerTestSupport.identity;
import static io.vidocq.runtime.core.banner.BannerTestSupport.jar;
import static io.vidocq.runtime.core.banner.BannerTestSupport.mode;
import static io.vidocq.runtime.core.banner.BannerTestSupport.prodByAbsence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The context line, its fitting, the bricks line and the built-in art. */
class StartupIdentityTest {

    private static final BuildInfo SNAPSHOT_DIRTY =
            jar("0.4.0-SNAPSHOT", "9beafc47", true, "2026-09-17T14:02:11Z", null, null);

    // ------------------------------------------------------------------ context line

    @Test
    void contextLineParts() {
        assertEquals("Java 25+36-LTS (Eclipse Adoptium) | dev (profile dev) | mcp-time-server 0.1.0-SNAPSHOT",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("profile dev"), "mcp-time-server", "0.1.0-SNAPSHOT")
                        .contextLine());
        assertEquals("Java 25+36-LTS | dev (IntelliJ agent) | io.vidocq.tools.lc4jcdi.mcptimeserver",
                identity(SNAPSHOT_DIRTY, null, dev("IntelliJ agent"), "io.vidocq.tools.lc4jcdi.mcptimeserver", null)
                        .contextLine());
        assertEquals("Java 25+36-LTS | prod (no dev or test signal)",
                identity(SNAPSHOT_DIRTY, null, prodByAbsence(), null, null).contextLine(),
                "a prod nothing proves says what it rests on");
        assertEquals("Java 25+36-LTS", identity(SNAPSHOT_DIRTY, null, null, null, "1.0").contextLine());
    }

    @Test
    void theDebuggerIsShownInEveryMode() {
        assertEquals("Java 25+36-LTS | prod (profile prod) | debug *:5005 suspend=y | mcp-time-server 0.1.0-SNAPSHOT",
                identity(SNAPSHOT_DIRTY, null, mode(LaunchMode.PROD, "profile prod"), "debug *:5005 suspend=y",
                        "mcp-time-server", "0.1.0-SNAPSHOT").contextLine());
        assertNull(identity(SNAPSHOT_DIRTY, null, dev("IntelliJ agent"), null, "app", "1.0").debug());
    }

    @Test
    void theVendorGoesFirstThenTheNameThenTheDebuggerThenTheReason() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("profile dev"), "debug *:5005",
                "mcp-time-server", "0.1.0-SNAPSHOT");

        assertEquals("Java 25+36-LTS (Eclipse Adoptium) | dev (profile dev) | debug *:5005"
                + " | mcp-time-server 0.1.0-SNAPSHOT", id.contextLine(101));
        assertEquals("Java 25+36-LTS | dev (profile dev) | debug *:5005 | mcp-time-server 0.1.0-SNAPSHOT",
                id.contextLine(100), "the vendor is worth neither the debugger nor the reason");
        assertEquals("Java 25+36-LTS | dev (profile dev) | debug *:5005 | mcp-time-se... 0.1.0-SNAPSHOT",
                id.contextLine(81), "the name is cut while the debugger still fits");
        assertEquals("Java 25+36-LTS | dev (profile dev) | mcp-time-server 0.1.0-SNAPSHOT", id.contextLine(70),
                "then the debugger goes, and the name comes back whole");
        assertEquals("Java 25+36-LTS | dev | mcp-time-server 0.1.0-SNAPSHOT", id.contextLine(60),
                "the reason goes last, the mode itself stays");
    }

    @Test
    void atTheWidthOfTheBlockTheModeAndTheDebuggerSurviveTheVendor() {
        int max = StartupBanner.WIDTH - 1;

        assertEquals("Java 25+36-LTS | dev (IntelliJ agent) | debug *:5005 | todo 1.0",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("IntelliJ agent"), "debug *:5005", "todo", "1.0")
                        .contextLine(max),
                "a 19-column vendor never costs the address a debugger attaches to");
        assertEquals("Java 25+36-LTS | dev (profile dev) | debug *:18099 | vidocq-r... 0.4.0-SNAPSHOT",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("profile dev"), "debug *:18099",
                        "vidocq-runtime-cassini-rest-example", "0.4.0-SNAPSHOT").contextLine(max),
                "the application name is cut before the debugger is given up");
        assertEquals("Java 25+36-LTS | prod (no dev or test signal) | acme-orders-gateway-api 1.4.2",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", prodByAbsence(), null, "acme-orders-gateway-api", "1.4.2")
                        .contextLine(max),
                "nor the mode the launch was resolved to");
    }

    @Test
    void theDevConsoleFollowsTheDebugger() {
        assertEquals("Java 25+36-LTS | dev (vidocq:dev reload loop) | debug *:5005 | devconsole :8888 | app 1.0",
                identity(SNAPSHOT_DIRTY, null, dev("vidocq:dev reload loop"), "debug *:5005", "devconsole :8888",
                        "app", "1.0").contextLine());
        assertEquals("Java 25+36-LTS | dev (IntelliJ agent) | devconsole 0.0.0.0:9000",
                identity(SNAPSHOT_DIRTY, null, dev("IntelliJ agent"), null, "devconsole 0.0.0.0:9000", null, null)
                        .contextLine());
    }

    @Test
    void theDevConsoleGoesAfterTheNameAndBeforeTheDebugger() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("profile dev"), "debug *:5005",
                "devconsole :8888", "mcp-time-server", "0.1.0-SNAPSHOT");

        assertEquals("Java 25+36-LTS | dev (profile dev) | debug *:5005 | devconsole :8888"
                + " | mcp-time-server 0.1.0-SNAPSHOT", id.contextLine(119), "the vendor goes first");
        assertEquals("Java 25+36-LTS | dev (profile dev) | debug *:5005 | devconsole :8888"
                + " | mcp-time-se... 0.1.0-SNAPSHOT", id.contextLine(100),
                "the name is cut while the console still fits");
        assertEquals("Java 25+36-LTS | dev (profile dev) | debug *:5005 | mcp-time-server 0.1.0-SNAPSHOT",
                id.contextLine(82), "then the console goes, and the name comes back whole");
        assertEquals("Java 25+36-LTS | dev (profile dev) | mcp-time-server 0.1.0-SNAPSHOT", id.contextLine(70),
                "then the debugger");
        assertEquals("Java 25+36-LTS | dev | mcp-time-server 0.1.0-SNAPSHOT", id.contextLine(60),
                "and the reason last");
    }

    @Test
    void atTheWidthOfTheBlockTheDevConsoleDropsBeforeTheDebugger() {
        int max = StartupBanner.WIDTH - 1;

        assertEquals("Java 25+36-LTS | dev (IntelliJ agent) | debug *:5005 | todo 1.0",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("IntelliJ agent"), "debug *:5005",
                        "devconsole :8888", "todo", "1.0").contextLine(max),
                "the configured port is a promise the console's own URL record keeps; the debugger's address is"
                        + " what attaching needs");
        assertEquals("Java 25+36-LTS | dev (IntelliJ agent) | devconsole :8888 | todo 1.0",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("IntelliJ agent"), null, "devconsole :8888",
                        "todo", "1.0").contextLine(max),
                "without a debugger, the console fits once the vendor is gone");
    }

    @Test
    void aFreePortHasNoSegment() {
        StartupIdentity id = StartupIdentity.collect(new StartupBanner.Launch(null, false, true, dev("profile dev"),
                null, null, null, new StartupBanner.DevConsole("127.0.0.1", 0)));

        assertNull(id.devConsole(), "port 0 is only known once bound: the console's URL record gives it");
        assertFalse(id.contextLine().contains("devconsole"), id.contextLine());
        assertNull(StartupIdentity.collect(BannerTestSupport.launch(null, dev("profile dev"), null)).devConsole(),
                "no console, no segment");
    }

    @Test
    void aProdNoSignalProvesLosesItsSegmentRatherThanItsReason() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", prodByAbsence(), "debug *:5005",
                "app", "1.0");

        assertEquals("Java 25+36-LTS | prod (no dev or test signal) | debug *:5005 | app 1.0", id.contextLine(70));
        assertEquals("Java 25+36-LTS | prod (no dev or test signal) | app 1.0", id.contextLine(60));
        assertEquals("Java 25+36-LTS | app 1.0", id.contextLine(50),
                "'prod' alone would claim more than the absence of a signal proves");
    }

    @Test
    void thenTheModuleNameIsAbbreviatedToKeepTheVersion() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("IntelliJ agent"),
                "io.vidocq.tools.lc4jcdi.mcptimeserver.administration", "0.1.0-SNAPSHOT");

        assertEquals("Java 25+36-LTS | dev | i.v.t.l.m.administration 0.1.0-SNAPSHOT", id.contextLine(65));
        assertEquals("i.v.t.l.mcptimeserver", StartupIdentity.abbreviate("io.vidocq.tools.lc4jcdi.mcptimeserver"));
        assertEquals("app", StartupIdentity.abbreviate("app"));
    }

    @Test
    void thenTheNameIsCutBeforeTheVersion() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("IntelliJ agent"),
                "vidocq-runtime-cassini-rest-example", "0.4.0-SNAPSHOT");

        assertEquals("Java 25+36-LTS | dev (IntelliJ agent) | vidocq-runtime... 0.4.0-SNAPSHOT",
                id.contextLine(72));
    }

    @Test
    void thenTheLineIsCut() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("IntelliJ agent"),
                "an-application-whose-artifact-id-has-no-dots-to-abbreviate", "0.1.0-SNAPSHOT");

        String line = id.contextLine(40);

        assertEquals(40, line.length());
        assertTrue(line.startsWith("Java 25+36-LTS | dev | an-applica"), line);
        assertTrue(line.endsWith("..."), line);
        assertEquals("Java 25+36-LTS | dev",
                identity(SNAPSHOT_DIRTY, null, dev("p".repeat(80)), null, null).contextLine(79),
                "a reason that cannot fit is dropped, never cut");
    }

    // ------------------------------------------------------------------ bricks line

    @Test
    void aBrickIsWorthALookWhenItsVersionDiffersItIsDirtyOrItIsASnapshotWithoutCommit() {
        BuildInfo runtime = jar("0.4.0-SNAPSHOT", "9beafc47", false, "2026-09-17T14:02:11Z", null, null);

        assertFalse(StartupIdentity.worthALook(jar("0.4.0-SNAPSHOT", "2d8ec095", false, null, null, null), runtime));
        assertTrue(StartupIdentity.worthALook(jar("0.3.0", "2d8ec095", false, null, null, null), runtime));
        assertTrue(StartupIdentity.worthALook(jar("0.4.0-SNAPSHOT", "2d8ec095", true, null, null, null), runtime));
        assertTrue(StartupIdentity.worthALook(jar("0.4.0-SNAPSHOT", null, null, null, null, null), runtime));
    }

    @Test
    void bricksAreNamedByTheirThirdSegmentOutsideTheRuntime() {
        assertEquals("vauban", StartupIdentity.brickOf("io.vidocq.vauban.core"));
        assertEquals("foy", StartupIdentity.brickOf("io.vidocq.foy.api"));
        assertNull(StartupIdentity.brickOf("io.vidocq.runtime.cli"));
        assertNull(StartupIdentity.brickOf("io.vidocq.tools.lc4jcdi.mcptimeserver"));
        assertNull(StartupIdentity.brickOf("org.eclipse.yasson"));
    }

    @Test
    void theBricksLineIsOneRecordOnlyWhenABrickIsWorthALook() {
        assertEquals(Optional.empty(), identity(SNAPSHOT_DIRTY, null, null, null, null).bricksLine());
        assertEquals(Optional.of("Vidocq bricks: chappe 0.4.0-SNAPSHOT (2d8ec095+dirty), vauban 0.3.0"),
                new StartupIdentity(SNAPSHOT_DIRTY, "25", null, null, null, null, null, null,
                        List.of("chappe 0.4.0-SNAPSHOT (2d8ec095+dirty)", "vauban 0.3.0")).bricksLine());
    }

    @Test
    void theIdentityOfThisJvmIsCollectedFromItsArchives() {
        StartupIdentity id = StartupIdentity.collect(new StartupBanner.Launch(null, false, true,
                dev("profile dev"), new DebugAgent("*:18095", true), StartupBanner.class.getModule(), null,
                new StartupBanner.DevConsole("127.0.0.1", 18096)));

        assertTrue(id.identityLine().startsWith("Vidocq "), id.identityLine());
        assertFalse(id.identityLine().contains("null"), id.identityLine());
        assertEquals(Runtime.version().toString(), id.javaVersion());
        assertEquals("dev (profile dev)", id.launch().text());
        assertEquals("debug *:18095 suspend=y", id.debug());
        assertEquals("devconsole :18096", id.devConsole());
        if (StartupBanner.class.getModule().isNamed()) {
            assertEquals("io.vidocq.runtime.core", id.appName(), "an exploded module is named by its module name");
        }
        id.bricks().forEach(b -> assertFalse(b.startsWith("runtime "), b));
    }

    // ------------------------------------------------------------------ built-in art

    @Test
    void theBuiltInArtIsTheNameInAtMostSixPrintableAsciiLinesOfAtMost40Columns() throws IOException {
        String resource = artResource();
        List<String> lines = StartupBanner.artLines(resource);

        assertTrue(lines.size() <= 6, lines.size() + " lines");
        assertEquals(BannerTestSupport.ART_FIRST_LINE, lines.getFirst(), "the name starts the art");
        assertEquals("                              |_|", lines.getLast(), "the descender of the q ends the art");
        for (String line : lines) {
            assertTrue(line.length() <= 40, line);
            assertFalse(line.endsWith(" "), "trailing space: [" + line + "]");
            for (char c : line.toCharArray()) {
                assertTrue(c >= 0x20 && c <= 0x7E, "not printable ASCII: " + (int) c + " in " + line);
            }
        }
    }

    @Test
    void theBlockWithARealisticIdentityFitsIn8LinesOf80Columns() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", dev("target/classes with a pom.xml above"),
                "debug *:5005 suspend=y", "io.vidocq.tools.lc4jcdi.mcptimeserver", "0.1.0-SNAPSHOT");

        String block = StartupBanner.render(id, BannerTestSupport.config(Map.of()), false);

        List<String> lines = block.lines().toList();
        assertTrue(lines.size() <= 8, block);
        lines.forEach(line -> assertTrue(line.length() <= 80, line));
        assertEquals(" Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)", lines.get(6));
        assertEquals(" Java 25+36-LTS | dev | io.vidocq.tools.lc4jcdi.mcptimeserver 0.1.0-SNAPSHOT", lines.get(7),
                "a 41-column reason and a 22-column debugger do not fit behind a 37-column module name:"
                        + " StartupBanner.debuggerRecord then repeats the address on a record of its own");
    }

    @Test
    void crlfArtLeavesNoCarriageReturn() throws IOException {
        String lf = artResource();
        String crlf = lf.replace("\n", "\r\n");

        List<String> lines = StartupBanner.artLines(crlf);

        assertEquals(StartupBanner.artLines(lf), lines);
        lines.forEach(line -> assertFalse(line.contains("\r"), line));
        assertFalse(StartupBanner.builtinArt(false).contains("\r"));
    }

    private static String artResource() throws IOException {
        try (InputStream in = StartupBanner.class.getResourceAsStream(StartupBanner.ART_RESOURCE)) {
            assertNotNull(in, "the built-in art is a resource");
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
