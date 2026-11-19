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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a failing method shows (spec §7): its class, its message cut at 500, a user:password@ masked. */
class FailuresTest {

    @Test
    void aUserAndPasswordBeforeAnAtAreMasked() {
        assertEquals("cannot reach jdbc:postgresql://***:***@db:5432/shop",
                Failures.mask("cannot reach jdbc:postgresql://app:s3cret@db:5432/shop"));
        assertEquals("no URL here: a:b c", Failures.mask("no URL here: a:b c"));
    }

    @Test
    void theClassThenTheMessageCutAt500() {
        assertEquals("java.lang.IllegalStateException", Failures.text(new IllegalStateException()));
        assertEquals("java.lang.IllegalArgumentException: " + "x".repeat(500) + "…",
                Failures.text(new IllegalArgumentException("x".repeat(600))));
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        assertEquals("ab…", Failures.cut("ab\uD83D\uDE00cd", 3));
        assertEquals("abc", Failures.cut("abc", 3));
    }

    @Test
    void passwordParametersAndAPasswordWithAnAtAreMasked() {
        assertEquals("jdbc:h2:mem:x;USER=sa;PASSWORD=***", Failures.mask("jdbc:h2:mem:x;USER=sa;PASSWORD=hunter2"));
        assertEquals("jdbc:postgresql://db/x?user=u&password=***&ssl=true",
                Failures.mask("jdbc:postgresql://db/x?user=u&password=hunter2&ssl=true"));
        assertEquals("pwd=*** then", Failures.mask("pwd=hunter2 then"));
        String masked = Failures.mask("jdbc:mysql://user:p@ss@db:3306/x");
        assertFalse(masked.contains("ss@db") || masked.contains("p@"), masked);
        assertTrue(masked.endsWith("@db:3306/x"), masked);
    }

    @Test
    void theSummaryIsOneLineAndTheBodyKeepsTheLines() {
        IllegalStateException failure = new IllegalStateException("NULL non permis\nNULL not allowed\r\nSQL: INSERT");
        assertEquals("java.lang.IllegalStateException: NULL non permis NULL not allowed SQL: INSERT",
                Failures.line(Failures.text(failure)));
        assertTrue(Failures.text(failure).contains("\nNULL not allowed"));
    }
}
