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
package io.vidocq.runtime.core.console;

import io.vidocq.runtime.core.console.ConsoleSupport.ColorMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleSupportTest {

    private static final String IDEA_AGENT =
            "-javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=51234:/Applications/IntelliJ IDEA.app/Contents/bin";

    private static ConsoleSupport console(String noColor, String term, String os, boolean terminal, String... args) {
        return new ConsoleSupport(noColor, term, os, terminal, List.of(args));
    }

    /** NO_COLOR (unset, empty, set) x mode, on a Linux terminal where auto would colour. */
    @ParameterizedTest(name = "NO_COLOR={0}, {1} -> {2}")
    @CsvSource(nullValues = "null", value = {
            "null, ALWAYS, true", "null, NEVER, false", "null, AUTO, true",
            "'',   ALWAYS, true", "'',   NEVER, false", "'',   AUTO, true",
            "1,    ALWAYS, false", "1,   NEVER, false", "1,    AUTO, false"})
    void aNonEmptyNoColorAlwaysWins(String noColor, ColorMode mode, boolean expected) {
        assertEquals(expected, console(noColor, "xterm-256color", "Linux", true).colors(mode));
    }

    @Test
    void autoColoursATerminalOnlyWhenThereIsOne() {
        assertTrue(console(null, "xterm", "Mac OS X", true).colors(ColorMode.AUTO));
        assertFalse(console(null, "xterm", "Mac OS X", false).colors(ColorMode.AUTO));
    }

    @Test
    void autoColoursIntellijsRunConsoleWhichIsNotATerminal() {
        ConsoleSupport intellij = console(null, null, "Mac OS X", false, "-Dfile.encoding=UTF-8", IDEA_AGENT);

        assertTrue(intellij.intellijConsole());
        assertTrue(intellij.colors(ColorMode.AUTO));
        assertTrue(console(null, null, "Windows 11", false, IDEA_AGENT).colors(ColorMode.AUTO),
                "IntelliJ decodes ANSI on Windows too");
    }

    @Test
    void onlyAJavaAgentArgumentDetectsIntellij() {
        assertFalse(console(null, null, "Linux", false, "-Didea.path=/opt/idea_rt.jar").intellijConsole());
        assertFalse(console(null, null, "Linux", false, "-javaagent:/opt/other-agent.jar").intellijConsole());
        assertFalse(console(null, null, "Linux", false).intellijConsole());
    }

    @Test
    void aDumbTerminalGetsNoColourInAuto() {
        assertFalse(console(null, "dumb", "Linux", true).colors(ColorMode.AUTO));
        assertFalse(console(null, "dumb", "Linux", false, IDEA_AGENT).colors(ColorMode.AUTO));
        assertTrue(console(null, "dumb", "Linux", true).colors(ColorMode.ALWAYS), "always still colours");
    }

    @Test
    void aWindowsTerminalGetsNoColourInAuto() {
        assertFalse(console(null, null, "Windows 11", true).colors(ColorMode.AUTO));
        assertTrue(console(null, null, "Windows 11", true).colors(ColorMode.ALWAYS));
    }

    @Test
    void aNullModeMeansAuto() {
        assertTrue(console(null, "xterm", "Linux", true).colors(null));
        assertFalse(console(null, "xterm", "Linux", false).colors(null));
    }

    @Test
    void parsesColorModes() {
        assertEquals(Optional.of(ColorMode.ALWAYS), ColorMode.parse(" Always "));
        assertEquals(Optional.of(ColorMode.NEVER), ColorMode.parse("never"));
        assertEquals(Optional.of(ColorMode.AUTO), ColorMode.parse("AUTO"));
        assertEquals(Optional.empty(), ColorMode.parse("colour"));
        assertEquals(Optional.empty(), ColorMode.parse(" "));
        assertEquals(Optional.empty(), ColorMode.parse(null));
    }

    @Test
    void earlySettingPrefersTheSystemPropertyThenTheEnvironment() {
        Map<String, String> env = Map.of("VIDOCQ_CONSOLE_COLOR", "never");

        assertEquals(Optional.of("always"), ConsoleSupport.earlySetting("vidocq.console.color",
                key -> key.equals("vidocq.console.color") ? " always " : null, env::get));
        assertEquals(Optional.of("never"), ConsoleSupport.earlySetting("vidocq.console.color",
                key -> null, env::get));
        assertEquals(Optional.of("jdk"), ConsoleSupport.earlySetting("vidocq.log.console",
                key -> "  ", Map.of("vidocq_log_console", "jdk")::get));
        assertEquals(Optional.empty(), ConsoleSupport.earlySetting("vidocq.log.console", key -> null, key -> null));
    }

    @Test
    void currentReadsThisJvmWithoutFailing() {
        ConsoleSupport current = ConsoleSupport.current();

        assertNotNull(current.jvmArguments());
        current.colors(ColorMode.AUTO);
    }
}
