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
package io.vidocq.runtime.cli;

import io.vidocq.runtime.cli.completion.Shell;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliParserM5Test {

    @Test
    void configGet() {
        Command.Config.Get g = assertInstanceOf(Command.Config.Get.class,
                CliParser.parse(new String[]{"config", "get", "vidocq.http.port"}));
        assertEquals("vidocq.http.port", g.key());
    }

    @Test
    void configSet() {
        Command.Config.Set s = assertInstanceOf(Command.Config.Set.class,
                CliParser.parse(new String[]{"config", "set", "vidocq.http.port", "9090"}));
        assertEquals("vidocq.http.port", s.key());
        assertEquals("9090", s.value());
    }

    @Test
    void configList() {
        assertInstanceOf(Command.Config.Listing.class,
                CliParser.parse(new String[]{"config", "list"}));
    }

    @Test
    void configGetRequiresKey() {
        assertThrows(CliException.class, () -> CliParser.parse(new String[]{"config", "get"}));
    }

    @Test
    void configSetRequiresValue() {
        assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"config", "set", "k"}));
    }

    @Test
    void configUnknownSubCommand() {
        assertThrows(CliException.class, () -> CliParser.parse(new String[]{"config", "wipe"}));
    }

    @Test
    void configNoSubCommand() {
        assertThrows(CliException.class, () -> CliParser.parse(new String[]{"config"}));
    }

    @Test
    void completionBash() {
        Command.Completion c = assertInstanceOf(Command.Completion.class,
                CliParser.parse(new String[]{"completion", "bash"}));
        assertEquals(Shell.BASH, c.shell());
    }

    @Test
    void completionZsh() {
        Command.Completion c = assertInstanceOf(Command.Completion.class,
                CliParser.parse(new String[]{"completion", "zsh"}));
        assertEquals(Shell.ZSH, c.shell());
    }

    @Test
    void completionInstallDetectsTheShellWhenOmitted() {
        Command.CompletionSetup c = assertInstanceOf(Command.CompletionSetup.class,
                CliParser.parse(new String[]{"completion", "install"}));
        assertEquals(null, c.shell());
        assertEquals(true, c.install());
    }

    @Test
    void completionUninstallTakesAnExplicitShell() {
        Command.CompletionSetup c = assertInstanceOf(Command.CompletionSetup.class,
                CliParser.parse(new String[]{"completion", "uninstall", "bash"}));
        assertEquals(Shell.BASH, c.shell());
        assertEquals(false, c.install());
    }

    @Test
    void completionRequiresShell() {
        assertThrows(CliException.class, () -> CliParser.parse(new String[]{"completion"}));
    }

    @Test
    void completionUnknownShell() {
        assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"completion", "fish"}));
    }

    @Test
    void unknownCommandBecomesPluginCommand() {
        Command.Plugin p = assertInstanceOf(Command.Plugin.class,
                CliParser.parse(new String[]{"deploy", "--env", "prod"}));
        assertEquals("deploy", p.name());
        assertEquals(List.of("--env", "prod"), p.args());
    }
}
