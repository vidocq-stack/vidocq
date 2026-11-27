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
package io.vidocq.chappe.grpc.client;

import java.time.Duration;

/**
 * The instant a call must be over by, on {@link System#nanoTime()}; {@link #NONE} for a call without deadline.
 *
 * @param set whether there is a deadline at all
 * @param atNanos the instant, meaningful only when {@code set}
 */
record Deadline(boolean set, long atNanos) {

    static final Deadline NONE = new Deadline(false, 0L);

    /** The deadline {@code timeout} from now; {@link #NONE} for {@code null} or a timeout too long to count. */
    static Deadline after(Duration timeout) {
        if (timeout == null) return NONE;
        try {
            return new Deadline(true, Math.addExact(System.nanoTime(), timeout.toNanos()));
        } catch (ArithmeticException _) {
            return NONE;
        }
    }

    /** The nanoseconds left, never negative; {@link Long#MAX_VALUE} without a deadline. */
    long remainingNanos() {
        return set ? Math.max(0L, atNanos - System.nanoTime()) : Long.MAX_VALUE;
    }

    boolean expired() {
        return set && atNanos - System.nanoTime() <= 0;
    }
}
