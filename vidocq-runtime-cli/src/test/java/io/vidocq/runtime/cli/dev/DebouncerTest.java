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
package io.vidocq.runtime.cli.dev;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebouncerTest {

    @Test
    void firstEventIsAlwaysAccepted() {
        AtomicLong clock = new AtomicLong(0);
        Debouncer debouncer = new Debouncer(Duration.ofMillis(300), clock::get);
        assertTrue(debouncer.accept());
    }

    @Test
    void eventsWithinWindowAreCollapsed() {
        AtomicLong clock = new AtomicLong(0);
        Debouncer debouncer = new Debouncer(Duration.ofMillis(300), clock::get);

        assertTrue(debouncer.accept());                                  // t=0 accepted
        clock.set(Duration.ofMillis(100).toNanos());
        assertFalse(debouncer.accept());                                 // +100ms collapsed
        clock.set(Duration.ofMillis(299).toNanos());
        assertFalse(debouncer.accept());                                 // +299ms still collapsed
    }

    @Test
    void eventAfterWindowIsAcceptedAgain() {
        AtomicLong clock = new AtomicLong(0);
        Debouncer debouncer = new Debouncer(Duration.ofMillis(300), clock::get);

        assertTrue(debouncer.accept());
        clock.set(Duration.ofMillis(300).toNanos());
        assertTrue(debouncer.accept());                                  // exactly at window
        clock.set(Duration.ofMillis(700).toNanos());
        assertTrue(debouncer.accept());                                  // well past window
    }
}
