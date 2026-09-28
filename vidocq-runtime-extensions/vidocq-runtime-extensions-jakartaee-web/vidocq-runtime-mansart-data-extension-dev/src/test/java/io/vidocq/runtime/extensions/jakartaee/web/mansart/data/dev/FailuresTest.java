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
}
