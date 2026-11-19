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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static io.vidocq.runtime.core.banner.BannerTestSupport.ART_FIRST_LINE;
import static io.vidocq.runtime.core.banner.BannerTestSupport.ESC;
import static io.vidocq.runtime.core.banner.BannerTestSupport.config;
import static io.vidocq.runtime.core.banner.BannerTestSupport.identity;
import static io.vidocq.runtime.core.banner.BannerTestSupport.jar;
import static io.vidocq.runtime.core.banner.BannerTestSupport.launch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq-banner.txt}, {@code vidocq.banner.location} and the placeholders. */
class CustomBannerTest {

    private static final StartupIdentity ID = identity(
            jar("0.4.0-SNAPSHOT", "9beafc47", true, "2026-09-17T14:02:11Z", null, null),
            "Eclipse Adoptium", null, "mcp-time-server", "0.1.0-SNAPSHOT");

    @TempDir
    Path dir;

    @Test
    void placeholderSyntax() {
        Map<String, String> values = Map.of("a", "A", "empty", "");

        assertEquals("[A] [] [fallback] [] [x:y]", BannerTemplate.resolve("[${a}] [${unknown}] [${unknown:fallback}] [${empty:z}] [${unknown:x:y}]",
                values::get));
        assertEquals("keep ${unterminated", BannerTemplate.resolve("keep ${unterminated", values::get));
        assertEquals("${a\nA}", BannerTemplate.resolve("${a\n${a}}", values::get), "a placeholder closes on its line");
        assertEquals("[A]", BannerTemplate.resolve("[${ a }]", values::get));
        assertEquals("[]", BannerTemplate.resolve("[${}]", values::get));
        assertTrue(BannerTemplate.references("x ${vidocq.identity:none} y", "vidocq.identity"));
        assertFalse(BannerTemplate.references("x $vidocq.identity y", "vidocq.identity"));
    }

    @Test
    void theDocumentedPlaceholders() {
        var config = config(Map.of("vidocq.chappe.listener.default.port", "18090"));

        assertEquals("0.4.0-SNAPSHOT", StartupBanner.placeholder("vidocq.version", ID, false, config));
        assertEquals("9beafc47+dirty, built 2026-09-17T14:02:11Z", StartupBanner.placeholder("vidocq.build", ID, false, config));
        assertEquals("Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)",
                StartupBanner.placeholder("vidocq.identity", ID, false, config));
        assertEquals("Java 25+36-LTS (Eclipse Adoptium)", StartupBanner.placeholder("java.version", ID, false, config));
        assertEquals("mcp-time-server", StartupBanner.placeholder("app.name", ID, false, config));
        assertEquals("0.1.0-SNAPSHOT", StartupBanner.placeholder("app.version", ID, false, config));
        assertEquals(null, StartupBanner.placeholder("vidocq.launch", ID, false, config));
        assertEquals(null, StartupBanner.placeholder("vidocq.debug", ID, false, config));
        StartupIdentity inDev = identity(jar("0.4.0-SNAPSHOT", "9beafc47", true, null, null, null), null,
                BannerTestSupport.dev("IntelliJ agent"), "debug *:5005", "mcp-time-server", "0.1.0-SNAPSHOT");
        assertEquals("dev (IntelliJ agent)", StartupBanner.placeholder("vidocq.launch", inDev, false, config));
        assertEquals("debug *:5005", StartupBanner.placeholder("vidocq.debug", inDev, false, config));
        assertEquals("18090", StartupBanner.placeholder("vidocq.chappe.listener.default.port", ID, false, config));
        assertEquals("", StartupBanner.placeholder("ansi.red", ID, false, config), "no colour: empty");
        assertEquals(ESC + "[31m", StartupBanner.placeholder("ansi.red", ID, true, config));
        assertEquals(null, StartupBanner.placeholder("ansi.chartreuse", ID, true, config));
        StartupIdentity release = identity(jar("0.3.0", "e1c8685e", false, null, null, null), null, null, null, null);
        assertEquals(null, StartupBanner.placeholder("vidocq.build", release, false, config), "empty for a clean release");
    }

    @Test
    void aFileBannerIsResolvedAndFollowedByTheGeneratedLines() throws Exception {
        Path banner = Files.writeString(dir.resolve("banner.txt"), """
                ${ansi.cyan}  M C P   T I M E   S E R V E R${ansi.reset}
                  listening on port ${vidocq.chappe.listener.default.port:8080} - app ${app.name} ${app.version}
                  [${no.such.key}] [${no.such.key:fallback}] [${vidocq.build}] ${unterminated
                """, StandardCharsets.UTF_8);

        String block = StartupBanner.render(ID, config(Map.of(StartupBanner.LOCATION_KEY, "file:" + banner)), false);

        assertEquals(List.of(
                "  M C P   T I M E   S E R V E R",
                "  listening on port 8080 - app mcp-time-server 0.1.0-SNAPSHOT",
                "  [] [fallback] [9beafc47+dirty, built 2026-09-17T14:02:11Z] ${unterminated",
                " Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)",
                " Java 25+36-LTS (Eclipse Adoptium) | mcp-time-server 0.1.0-SNAPSHOT"), block.lines().toList());
    }

    @Test
    void aBannerThatPlacesTheIdentityGetsNoGeneratedLines() throws Exception {
        Path banner = Files.writeString(dir.resolve("with-identity.txt"), "  MCP TIME SERVER\r\n  ${vidocq.identity}");

        String block = StartupBanner.render(ID, config(Map.of(StartupBanner.LOCATION_KEY, banner.toString())), true);

        assertEquals("  MCP TIME SERVER\n  Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)\n", block);
    }

    @Test
    void aClasspathLocationAndTheDefaultResourceAreReadThroughTheContextClassLoader() throws Exception {
        Files.writeString(dir.resolve("vidocq-banner.txt"), "DEFAULT ${app.name}\n", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("banners"));
        Files.writeString(dir.resolve("banners/mine.txt"), "MINE é\n", StandardCharsets.UTF_8);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);

            assertTrue(StartupBanner.render(ID, config(Map.of()), false).startsWith("DEFAULT mcp-time-server\n Vidocq "));
            assertTrue(StartupBanner.render(ID, config(Map.of(StartupBanner.LOCATION_KEY, "classpath:/banners/mine.txt")),
                    false).startsWith("MINE é\n Vidocq "), "read as UTF-8");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void aMissingBannerWarnsAndFallsBackToTheBuiltInArt() {
        try (Records records = new Records()) {
            String block = StartupBanner.render(ID, config(Map.of(StartupBanner.LOCATION_KEY, "file:custom/nope.txt")), false);

            assertTrue(block.startsWith(ART_FIRST_LINE + "\n"), block);
            assertEquals(List.of("Banner vidocq.banner.location=file:custom/nope.txt not found; using the built-in banner"),
                    records.messages(Level.WARNING));
        }
    }

    @Test
    void aFileWithASpaceIsReadAsAnEncodedUriAPrefixedPathOrAPlainPath() throws Exception {
        Path banner = Files.writeString(dir.resolve("my banner.txt"), "SPACED\n", StandardCharsets.UTF_8);

        for (String location : List.of(banner.toUri().toString(), "file:" + banner, banner.toString())) {
            try (Records records = new Records()) {
                String block = StartupBanner.render(ID, config(Map.of(StartupBanner.LOCATION_KEY, location)), false);

                assertTrue(block.startsWith("SPACED\n Vidocq "), location + " -> " + block);
                assertEquals(List.of(), records.messages(Level.WARNING), location);
            }
        }
        assertTrue(banner.toUri().toString().startsWith("file:///") && banner.toUri().toString().contains("%20"),
                "the URI form is the encoded file:/// one: " + banner.toUri());
    }

    /** A space left unencoded in a URI, an authority, a query, a NUL character: none is a file. */
    @ParameterizedTest
    @ValueSource(strings = {
            "file:///tmp/my banner.txt",
            "file:///C:/Program Files/app/banner.txt",
            "file://relative/banner.txt",
            "file:///tmp/banner.txt?v=1",
            "file:custom/\0banner.txt",
            "custom/\0banner.txt"})
    void anUnparsableLocationWarnsAndFallsBackToTheBuiltInArt(String location) {
        try (Records records = new Records()) {
            String block = StartupBanner.render(ID, config(Map.of(StartupBanner.LOCATION_KEY, location)), false);

            assertTrue(block.startsWith(ART_FIRST_LINE + "\n"), block);
            assertTrue(block.contains("\n Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)\n"), block);
            List<String> warnings = records.messages(Level.WARNING);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().startsWith("Banner vidocq.banner.location=" + location + " is not a file location ("),
                    warnings.getFirst());
            assertTrue(warnings.getFirst().endsWith("); using the built-in banner"), warnings.getFirst());
        }
    }

    @Test
    void anUnparsableLocationStillPrintsTheArtAndTheIdentity() {
        Out out = new Out();
        StartupBanner.reset();
        try (Records records = new Records()) {
            assertTrue(StartupBanner.showOnce(config(Map.of(StartupBanner.LOCATION_KEY, "file:///tmp/my banner.txt")),
                    () -> launch(BannerMode.CONSOLE), BannerTestSupport::pipe, out.stream));

            List<String> lines = out.text().lines().toList();
            assertEquals(ART_FIRST_LINE, lines.getFirst(), out.text());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith(" Vidocq ")), out.text());
            assertTrue(lines.getLast().startsWith(" Java "), out.text());
            assertTrue(StartupBanner.emittedIdentity().isPresent());
            assertEquals(1, records.messages(Level.WARNING).size());
        } finally {
            StartupBanner.reset();
        }
    }
}
