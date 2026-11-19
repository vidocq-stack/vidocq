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

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/** Waits for what a test expects by checking it again until it holds, against a deadline, instead of sleeping. */
final class Poll {

    private static final long INTERVAL_NANOS = Duration.ofMillis(10).toNanos();

    private Poll() {}

    /** A condition to check; it may fail the way the code it reads fails. */
    @FunctionalInterface
    interface Condition<E extends Exception> {
        boolean holds() throws E;
    }

    /**
     * Checks {@code condition} until it holds or {@code timeout} passes.
     *
     * @return whether it held
     * @throws InterruptedException if the waiting thread is interrupted
     */
    static <E extends Exception> boolean until(Duration timeout, Condition<E> condition)
            throws E, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.holds()) {
            if (System.nanoTime() - deadline > 0) {
                return false;
            }
            LockSupport.parkNanos(INTERVAL_NANOS);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        return true;
    }
}
