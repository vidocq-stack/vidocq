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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TerminalControls.Command;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerminalControlsTest {

    @Test
    void eachKeyAndEnter() {
        assertEquals(Optional.of(Command.RUN_ALL), TerminalControls.parse("r"));
        assertEquals(Optional.of(Command.RERUN_FAILED), TerminalControls.parse("F"));
        assertEquals(Optional.of(Command.QUIT), TerminalControls.parse("q"));
        assertEquals(Optional.of(Command.RUN_ALL), TerminalControls.parse(""), "Enter alone");
        assertEquals(Optional.of(Command.RERUN_FAILED), TerminalControls.parse("  failed please"),
                "the first character decides");
        assertEquals(Optional.empty(), TerminalControls.parse("x"));
    }

    @Test
    void linesAreReadUntilQuit() {
        List<Command> seen = new ArrayList<>();
        byte[] typed = "r\nzzz\nf\nq\nr\n".getBytes(StandardCharsets.UTF_8);

        new TerminalControls(new ByteArrayInputStream(typed), seen::add).run();

        assertEquals(List.of(Command.RUN_ALL, Command.RERUN_FAILED, Command.QUIT), seen);
    }

    @Test
    void theEndOfTheInputEndsTheReading() {
        List<Command> seen = new ArrayList<>();

        new TerminalControls(new ByteArrayInputStream(new byte[0]), seen::add).run();

        assertEquals(List.of(), seen);
    }

    /** Surefire's forked JVM reads a pipe: on JDK 22+ System.console() is not null there, but it is no terminal. */
    @Test
    void aPipedStandardInputIsNoConsole() {
        assertFalse(TerminalControls.attached());
    }
}
