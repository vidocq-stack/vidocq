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
package io.vidocq.runtime.cli.spi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliPluginsTest {

    private record FakePlugin(String command, int exit) implements VidocqCliPlugin {
        @Override public String description() { return "fake " + command; }
        @Override public int run(String[] args) { return exit; }
    }

    @Test
    void findMatchingPlugin() {
        var deploy = new FakePlugin("deploy", 0);
        var lint = new FakePlugin("lint", 0);
        assertSame(deploy, CliPlugins.find(List.of(deploy, lint), "deploy").orElseThrow());
    }

    @Test
    void findReturnsEmptyWhenNoMatch() {
        var deploy = new FakePlugin("deploy", 0);
        assertTrue(CliPlugins.find(List.of(deploy), "ship").isEmpty());
    }

    @Test
    void firstMatchWins() {
        var a = new FakePlugin("dup", 1);
        var b = new FakePlugin("dup", 2);
        assertSame(a, CliPlugins.find(List.of(a, b), "dup").orElseThrow());
    }

    @Test
    void findOnEmptyList() {
        assertTrue(CliPlugins.find(List.of(), "x").isEmpty());
    }

    @Test
    void runReturnsPluginExitCode() {
        var plugin = new FakePlugin("deploy", 42);
        assertEquals(42, plugin.run(new String[0]));
    }

    @Test
    void allScansClasspathWithoutError() {
        // No providers are registered in the test module, so this is empty,
        // but it must not throw.
        assertTrue(CliPlugins.all().isEmpty());
    }
}
