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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.vidocq.runtime.core.banner.BannerTestSupport.identity;
import static io.vidocq.runtime.core.banner.BannerTestSupport.jar;
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
        assertEquals("Java 25+36-LTS (Eclipse Adoptium) | profile dev | mcp-time-server 0.1.0-SNAPSHOT",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", "profile dev", "mcp-time-server", "0.1.0-SNAPSHOT")
                        .contextLine());
        assertEquals("Java 25+36-LTS | dev launch | io.vidocq.tools.lc4jcdi.mcptimeserver",
                identity(SNAPSHOT_DIRTY, null, "dev launch", "io.vidocq.tools.lc4jcdi.mcptimeserver", null).contextLine());
        assertEquals("Java 25+36-LTS", identity(SNAPSHOT_DIRTY, null, null, null, "1.0").contextLine());
    }

    @Test
    void launchText() {
        assertEquals("profile dev", StartupIdentity.launchText("dev", true));
        assertEquals("profile prod", StartupIdentity.launchText("prod", false));
        assertEquals("dev launch", StartupIdentity.launchText(null, true));
        assertEquals("dev launch", StartupIdentity.launchText(" ", true));
        assertNull(StartupIdentity.launchText(null, false), "no signal proves nothing: never 'prod' by default");
    }

    @Test
    void theVendorGoesFirst() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", "profile dev", "mcp-time-server",
                "0.1.0-SNAPSHOT");

        // with the vendor the rendered line would be 81 columns
        assertEquals("Java 25+36-LTS | profile dev | mcp-time-server 0.1.0-SNAPSHOT", id.contextLine(79));
        assertEquals("Java 25+36-LTS (Eclipse Adoptium) | mcp-time-server 0.1.0-SNAPSHOT",
                identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", null, "mcp-time-server", "0.1.0-SNAPSHOT").contextLine(79));
    }

    @Test
    void thenTheModuleNameIsAbbreviatedToKeepTheVersion() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", "dev launch",
                "io.vidocq.tools.lc4jcdi.mcptimeserver.administration", "0.1.0-SNAPSHOT");

        assertEquals("Java 25+36-LTS | dev launch | i.v.t.l.m.administration 0.1.0-SNAPSHOT", id.contextLine(70));
        assertEquals("i.v.t.l.mcptimeserver", StartupIdentity.abbreviate("io.vidocq.tools.lc4jcdi.mcptimeserver"));
        assertEquals("app", StartupIdentity.abbreviate("app"));
    }

    @Test
    void thenTheNameIsCutBeforeTheVersion() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", "dev launch",
                "vidocq-runtime-cassini-rest-example", "0.4.0-SNAPSHOT");

        assertEquals("Java 25+36-LTS | dev launch | vidocq-runtime-cassini-rest-exa... 0.4.0-SNAPSHOT", id.contextLine(79));
    }

    @Test
    void thenTheLineIsCut() {
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium",
                "profile a-profile-name-so-long-that-the-application-cannot-fit-anymore",
                "an-application-whose-artifact-id-has-no-dots-to-abbreviate", "0.1.0-SNAPSHOT");

        String line = id.contextLine(79);

        assertEquals(79, line.length());
        assertTrue(line.startsWith("Java 25+36-LTS | profile a-profile-name-so-long"), line);
        assertTrue(line.endsWith("..."), line);
        assertEquals("Java 25+36-LTS | profile " + "p".repeat(51) + "...",
                identity(SNAPSHOT_DIRTY, null, "profile " + "p".repeat(80), null, null).contextLine(79));
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
                new StartupIdentity(SNAPSHOT_DIRTY, "25", null, null, null, null,
                        List.of("chappe 0.4.0-SNAPSHOT (2d8ec095+dirty)", "vauban 0.3.0")).bricksLine());
    }

    @Test
    void theIdentityOfThisJvmIsCollectedFromItsArchives() {
        StartupIdentity id = StartupIdentity.collect(new StartupBanner.Launch(null, false, true, false, "dev",
                StartupBanner.class.getModule(), null));

        assertTrue(id.identityLine().startsWith("Vidocq "), id.identityLine());
        assertFalse(id.identityLine().contains("null"), id.identityLine());
        assertEquals(Runtime.version().toString(), id.javaVersion());
        assertEquals("profile dev", id.launch());
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
        StartupIdentity id = identity(SNAPSHOT_DIRTY, "Eclipse Adoptium", "profile dev",
                "io.vidocq.tools.lc4jcdi.mcptimeserver", "0.1.0-SNAPSHOT");

        String block = StartupBanner.render(id, BannerTestSupport.config(Map.of()), false);

        List<String> lines = block.lines().toList();
        assertTrue(lines.size() <= 8, block);
        lines.forEach(line -> assertTrue(line.length() <= 80, line));
        assertEquals(" Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)", lines.get(6));
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
