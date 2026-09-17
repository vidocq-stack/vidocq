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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.maven.ConsoleColors.Choice;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour policy the forked JVM inherits from Maven.
 *
 * <p>The values of {@code jansi.mode} are the measured ones (Maven 3.9.16, output piped): {@code force} for
 * {@code -Dstyle.color=always} and for {@code --color=always}, which is what IntelliJ's Maven runner sends
 * ({@code -Djansi.passthrough=true -Dstyle.color=always}); {@code strip} for {@code never}, for
 * {@code --color=never} and for {@code -B}; unset when nothing asks.
 */
class ConsoleColorsTest {

    @Test
    void mavenColoured_colonTheChildColoursToo() {
        Optional<Choice> choice = ConsoleColors.forChild(false, "force", "always", null);

        assertEquals("always", choice.orElseThrow().mode());
        assertEquals("Maven's own output is coloured (jansi.mode=force)", choice.orElseThrow().reason());
        assertTrue(choice.orElseThrow().logLine()
                .endsWith("— the forked JVM gets -Dvidocq.console.color=always"), choice.toString());
    }

    @Test
    void mavenNotColoured_colonTheChildDoesNotEither() {
        // -B, --color=never or -Dstyle.color=never: Maven strips its own colours, and a log file or a CI
        // console must not get escape sequences from the application either.
        assertEquals("never", ConsoleColors.forChild(false, "strip", null, null).orElseThrow().mode());
        assertEquals("Maven's own output is not coloured (jansi.mode=strip)",
                ConsoleColors.forChild(false, "strip", null, null).orElseThrow().reason());
    }

    @Test
    void noSignal_colonNothingIsPassedAndTheRuntimeDecides() {
        assertEquals(Optional.empty(), ConsoleColors.forChild(false, null, null, null));
        assertEquals(Optional.empty(), ConsoleColors.forChild(false, "", " ", ""));
        assertEquals(Optional.empty(), ConsoleColors.forChild(false, null, "auto", null));
        assertEquals(Optional.empty(), ConsoleColors.forChild(false, "unknown-mode", null, null));
    }

    /** Maven 4 replaces jansi with JLine: {@code style.color}, which {@code MavenCli} reads, still answers. */
    @Test
    void withoutJansi_colonTheStyleColorPropertyAnswers() {
        assertEquals("always", ConsoleColors.forChild(false, null, "always", null).orElseThrow().mode());
        assertEquals("always", ConsoleColors.forChild(false, null, "YES", null).orElseThrow().mode());
        assertEquals("always", ConsoleColors.forChild(false, null, "force", null).orElseThrow().mode());
        assertEquals("never", ConsoleColors.forChild(false, null, "never", null).orElseThrow().mode());
        assertEquals("never", ConsoleColors.forChild(false, null, " None ", null).orElseThrow().mode());
        assertEquals("Maven's own output is coloured (style.color=always)",
                ConsoleColors.forChild(false, null, "always", null).orElseThrow().reason());
    }

    /** {@code jansi.mode} is the only signal that catches {@code --color}, so it wins over the user property. */
    @Test
    void jansiMode_winsOverTheStyleColorProperty() {
        assertEquals("always", ConsoleColors.forChild(false, "force", "never", null).orElseThrow().mode());
        assertEquals("never", ConsoleColors.forChild(false, "strip", "always", null).orElseThrow().mode());
    }

    @Test
    void anExplicitColourPolicy_isNeverOverwritten() {
        assertEquals(Optional.empty(), ConsoleColors.forChild(true, "force", "always", null));
        assertEquals(Optional.empty(), ConsoleColors.forChild(true, "strip", "never", null));
    }

    /** The child honours {@code NO_COLOR} itself, and it wins over every mode: saying it again hides why. */
    @Test
    void noColor_stopsTheDecision() {
        assertEquals(Optional.empty(), ConsoleColors.forChild(false, "force", "always", "1"));
        assertEquals(Optional.empty(), ConsoleColors.forChild(false, "strip", null, "yes"));
        // NO_COLOR= (empty) is not a request, as ConsoleSupport reads it.
        assertEquals("always", ConsoleColors.forChild(false, "force", null, "").orElseThrow().mode());
    }

    @Test
    void theKeyIsTheOneTheRuntimeReads() {
        assertEquals("vidocq.console.color", ConsoleColors.COLOR_KEY);
    }
}
