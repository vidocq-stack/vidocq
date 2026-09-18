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

import io.vidocq.runtime.spi.report.LaunchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's settings: the enable and port rules it shares with the core (the tables of
 * {@code devconsole-enabled.csv} and {@code devconsole-port.csv}, read from the core's test resources), the host,
 * and the {@code VIDOCQ-DEVC-003} messages of the values it does not accept.
 */
class DevConsoleSettingsTest {

    @ParameterizedTest
    @CsvFileSource(resources = "/devconsole-enabled.csv", nullValues = "(unset)", useHeadersInDisplayName = true)
    void theEnableRuleIsTheCores(String enabled, String launchMode, boolean on, boolean valid) {
        LaunchMode mode = LaunchMode.parse(launchMode).orElseThrow();

        assertEquals(on, DevConsoleSettings.enabled(enabled, mode));
        assertEquals(valid, DevConsoleSettings.validEnabled(enabled));
        DevConsoleSettings settings = DevConsoleSettings.resolve(
                FakeExtensionContext.of(mode, enabled == null ? new String[0]
                        : new String[] {DevConsoleSettings.ENABLED_KEY, enabled}).config(), mode);
        assertEquals(on, settings.on());
        assertEquals(valid, settings.invalid().isEmpty(), settings.invalid().toString());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/devconsole-port.csv", nullValues = "(unset)", useHeadersInDisplayName = true)
    void thePortRuleIsTheCores(String port, int asked, boolean valid) {
        assertEquals(asked, DevConsoleSettings.port(port));
        assertEquals(valid, DevConsoleSettings.validPort(port));
    }

    @Test
    void theDefaultsListenOnTheLoopbackPort8888InADevLaunchOnly() {
        DevConsoleSettings dev = DevConsoleSettings.resolve(FakeExtensionContext.of(LaunchMode.DEV).config(),
                LaunchMode.DEV);
        DevConsoleSettings prod = DevConsoleSettings.resolve(FakeExtensionContext.of(LaunchMode.PROD).config(),
                LaunchMode.PROD);

        assertTrue(dev.on());
        assertEquals("127.0.0.1", dev.host());
        assertEquals(8888, dev.port());
        assertEquals("auto", dev.enabled());
        assertFalse(prod.on());
        assertEquals("off (auto, launch mode prod)", prod.offReason());
        assertEquals(List.of(), prod.invalid());
    }

    @Test
    void forcedOffSaysSo() {
        DevConsoleSettings off = DevConsoleSettings.resolve(FakeExtensionContext.of(LaunchMode.DEV,
                DevConsoleSettings.ENABLED_KEY, " False ").config(), LaunchMode.DEV);

        assertFalse(off.on());
        assertEquals("false", off.enabled());
        assertEquals("off (vidocq.devconsole.enabled=false)", off.offReason());
    }

    @Test
    void everyRejectedValueHasItsMessageAndTheDefaultIsUsed() {
        DevConsoleSettings settings = DevConsoleSettings.resolve(FakeExtensionContext.of(LaunchMode.DEV,
                DevConsoleSettings.ENABLED_KEY, "yes",
                DevConsoleSettings.PORT_KEY, "99999",
                DevConsoleSettings.HOST_KEY, " evil\u001B[31mhost ").config(), LaunchMode.DEV);

        assertTrue(settings.on(), "an invalid value reads as auto");
        assertEquals(8888, settings.port());
        assertEquals("127.0.0.1", settings.host());
        assertEquals(List.of(
                "Invalid value 'yes' for vidocq.devconsole.enabled (auto, true, false): using auto",
                "Invalid value '99999' for vidocq.devconsole.port (0 to 65535): using 8888",
                "Invalid value 'evil\u001B[31mhost' for vidocq.devconsole.host (a host name or an IP address): "
                        + "using 127.0.0.1"),
                settings.invalid(), "the report cleans them when it logs them");
    }

    @Test
    void theHostIsStrippedAndItsDefaultIsTheLoopbackAddress() {
        assertEquals("127.0.0.1", DevConsoleSettings.host(null));
        assertEquals("127.0.0.1", DevConsoleSettings.host("   "));
        assertEquals("0.0.0.0", DevConsoleSettings.host(" 0.0.0.0 "));
        assertEquals("::1", DevConsoleSettings.host("[::1]"), "Chappe binds the literal, brackets off");
        assertTrue(DevConsoleSettings.validHost(null));
        assertTrue(DevConsoleSettings.validHost("dev.example.com"));
        assertTrue(DevConsoleSettings.validHost("fe80::1%lo0"));
        assertFalse(DevConsoleSettings.validHost("a host"));
        assertFalse(DevConsoleSettings.validHost("x\u202Ey"));
    }
}
