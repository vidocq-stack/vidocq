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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.vidocq.runtime.core.banner.BannerTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the core knows of the dev console before any extension is loaded: whether its class is there, the enable,
 * port and host rules it shares with the console (the tables of {@code devconsole-enabled.csv},
 * {@code devconsole-port.csv} and {@code devconsole-host.csv}), and the banner segment that promises the configured
 * address.
 */
class DevConsoleProbeTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ the rules shared with the console

    @ParameterizedTest
    @CsvFileSource(resources = "/devconsole-enabled.csv", nullValues = "(unset)", useHeadersInDisplayName = true)
    void theEnableRule(String enabled, String launchMode, boolean on, boolean valid) {
        LaunchMode mode = LaunchMode.parse(launchMode).orElseThrow();

        assertEquals(on, DevConsoleProbe.enabled(enabled, mode));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/devconsole-port.csv", nullValues = "(unset)", useHeadersInDisplayName = true)
    void thePortRule(String port, int asked, boolean valid) {
        assertEquals(asked, DevConsoleProbe.port(port));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/devconsole-host.csv", nullValues = "(unset)", useHeadersInDisplayName = true)
    void theHostRule(String host, String listened, boolean valid) {
        assertEquals(listened, DevConsoleProbe.host(host));
    }

    @Test
    void anUnsetOrBlankHostIsTheLoopbackAddress() {
        assertEquals("127.0.0.1", DevConsoleProbe.host(null));
        assertEquals("127.0.0.1", DevConsoleProbe.host("  "));
        assertEquals("0.0.0.0", DevConsoleProbe.host(" 0.0.0.0 "));
    }

    @Test
    void aHostTheConsoleRefusesIsTheLoopbackAddress() {
        assertEquals("127.0.0.1", DevConsoleProbe.host("hôte"), "the console listens on 127.0.0.1 instead");
        assertEquals("127.0.0.1", DevConsoleProbe.host("x\u202Ey"), "a bidirectional override");
        assertEquals("127.0.0.1", DevConsoleProbe.host("h".repeat(256)), "longer than any host name");
        assertEquals("h".repeat(255), DevConsoleProbe.host("h".repeat(255)));
    }

    // ------------------------------------------------------------------ the banner segment

    @ParameterizedTest(name = "{0}:{1} -> {2}")
    @CsvSource(nullValues = "null", value = {
            "127.0.0.1,       8888, devconsole :8888",
            "localhost,       8888, devconsole :8888",
            "LocalHost,       9000, devconsole :9000",
            "127.0.1.1,       8888, devconsole :8888",
            "::1,             8888, devconsole :8888",
            "[::1],           8888, devconsole :8888",
            "0.0.0.0,         8888, devconsole 0.0.0.0:8888",
            "192.168.1.20,    8888, devconsole 192.168.1.20:8888",
            "dev.example.com, 9000, devconsole dev.example.com:9000",
            "fe80::1,         8888, devconsole [fe80::1]:8888",
            "[fe80::1],       8888, devconsole [fe80::1]:8888",
            "127.0.0.1,       0,    null",
            "0.0.0.0,         0,    null"})
    void theSegmentPromisesTheConfiguredAddressAndSaysNothingOfAFreePort(String host, int port, String segment) {
        assertEquals(segment, DevConsoleProbe.segment(host, port));
    }

    @Test
    void aHostThatCouldBreakTheLineHasNoSegment() {
        assertNull(DevConsoleProbe.segment("evil\n[VIDOCQ-FAKE] forged", 8888));
        assertNull(DevConsoleProbe.segment("a host", 8888));
        assertNull(DevConsoleProbe.segment("x‮y", 8888), "a bidirectional override");
        assertNull(DevConsoleProbe.segment("hôte", 8888), "a letter outside ASCII, which the console refuses");
        assertNull(DevConsoleProbe.segment("١٢٧.٠.٠.١", 8888), "digits outside ASCII");
    }

    // ------------------------------------------------------------------ the probe

    @Test
    void theConsoleIsOnlyThereWhenItsClassIs() throws Exception {
        Function<String, Optional<String>> nothing = config(Map.of());

        assertNull(DevConsoleProbe.probe(nothing, LaunchMode.DEV, getClass().getClassLoader()),
                "no console on the core's own path");
        try (URLClassLoader loader = DevConsoleFixture.withConsole(dir)) {
            assertEquals(new StartupBanner.DevConsole("127.0.0.1", 8888),
                    DevConsoleProbe.probe(nothing, LaunchMode.DEV, loader));
            assertNull(DevConsoleProbe.probe(nothing, LaunchMode.PROD, loader), "auto is off outside dev");
        }
    }

    @Test
    void theProbeReadsTheThreeKeys() throws Exception {
        Function<String, Optional<String>> configured = config(Map.of(
                DevConsoleProbe.ENABLED_KEY, "true",
                DevConsoleProbe.HOST_KEY, "0.0.0.0",
                DevConsoleProbe.PORT_KEY, "0"));

        try (URLClassLoader loader = DevConsoleFixture.withConsole(dir)) {
            StartupBanner.DevConsole console = DevConsoleProbe.probe(configured, LaunchMode.PROD, loader);

            assertEquals(new StartupBanner.DevConsole("0.0.0.0", 0), console);
            assertNull(console.segment(), "a free port is only known once bound");
            assertNull(DevConsoleProbe.probe(config(Map.of(DevConsoleProbe.ENABLED_KEY, "false")), LaunchMode.DEV,
                    loader));
        }
    }

    @Test
    void theBannerPromisesTheHostTheConsoleListensOn() throws Exception {
        try (URLClassLoader loader = DevConsoleFixture.withConsole(dir)) {
            StartupBanner.DevConsole refused = DevConsoleProbe.probe(config(Map.of(DevConsoleProbe.HOST_KEY, "hôte")),
                    LaunchMode.DEV, loader);
            StartupBanner.DevConsole bracketed = DevConsoleProbe.probe(
                    config(Map.of(DevConsoleProbe.HOST_KEY, "[fe80::1]")), LaunchMode.DEV, loader);

            assertEquals(new StartupBanner.DevConsole("127.0.0.1", 8888), refused,
                    "the console reports VIDOCQ-DEVC-003 and listens on 127.0.0.1");
            assertEquals("devconsole :8888", refused.segment());
            assertEquals(new StartupBanner.DevConsole("fe80::1", 8888), bracketed, "listened on without brackets");
            assertEquals("devconsole [fe80::1]:8888", bracketed.segment());
        }
    }

    @Test
    void aConfigurationThatCannotBeReadIsUnset() throws Exception {
        Function<String, Optional<String>> failing = key -> {
            throw new IllegalStateException("unreadable " + key);
        };

        try (URLClassLoader loader = DevConsoleFixture.withConsole(dir)) {
            assertEquals(new StartupBanner.DevConsole("127.0.0.1", 8888),
                    DevConsoleProbe.probe(failing, LaunchMode.DEV, loader));
        }
        assertNull(DevConsoleProbe.probe(config(Map.of()), LaunchMode.DEV, null), "no loader, no console");
    }
}
