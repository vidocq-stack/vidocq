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
package io.vidocq.runtime.devservices.junit;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Under {@code mvn test}, the dev services of the run log one line in Maven's format, its parameters formatted the
 * same in every locale, as under the goals: through the JDK's default logger they came out in two lines, the date
 * and the level in the machine's language (BUG-20261030-21).
 */
class ConsoleSystemLoggerTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final ConsoleSystemLogger log =
            new ConsoleSystemLogger(new PrintStream(bytes, true, StandardCharsets.UTF_8));

    private String out() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void anInfoMessageIsOneMavenLine() {
        log.log(System.Logger.Level.INFO, "Dev service {0} started on port {1}", "postgres", "33003");

        assertEquals("[INFO] Dev service postgres started on port 33003" + System.lineSeparator(), out());
    }

    @Test
    void warningsAndErrorsCarryTheirLevelAndCause() {
        log.log(System.Logger.Level.WARNING, "no Docker");
        log.log(System.Logger.Level.ERROR, "failed", new IllegalStateException("boom"));

        String out = out();
        assertTrue(out.startsWith("[WARNING] no Docker" + System.lineSeparator() + "[ERROR] failed"), out);
        assertTrue(out.contains("IllegalStateException: boom"), out);
    }

    @Test
    void debugIsDropped() {
        log.log(System.Logger.Level.DEBUG, "detail");

        assertFalse(log.isLoggable(System.Logger.Level.DEBUG));
        assertEquals("", out());
    }
}
