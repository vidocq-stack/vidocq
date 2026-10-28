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
package io.vidocq.runtime.extensions.essentials.devconsole;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/** Waits for what a test expects by reading it again until it shows, against a deadline, instead of sleeping. */
final class Poll {

    private static final long INTERVAL_NANOS = Duration.ofMillis(20).toNanos();

    private Poll() {}

    /**
     * Reads {@code probe} until {@code done} accepts the value or {@code timeout} passes, and returns the last value
     * read: the caller asserts on it, so a timeout shows what was seen.
     */
    static <T> T until(Duration timeout, Callable<T> probe, Predicate<T> done) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        T value = probe.call();
        while (!done.test(value) && System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(INTERVAL_NANOS);
            value = probe.call();
        }
        return value;
    }
}
