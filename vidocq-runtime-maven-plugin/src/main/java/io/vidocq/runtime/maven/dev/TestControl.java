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

import java.util.function.BooleanSupplier;

/**
 * What the source loop of {@code vidocq:dev} and {@code vidocq:test} asks of continuous testing (spec §2.2);
 * {@link ContinuousTesting} is the implementation, and the loop's tests use a recording one.
 */
interface TestControl {

    /**
     * A change: the run in flight is cancelled, and a run of every test is due once {@code gate} opens. It
     * replaces any run waiting to start.
     */
    void changed(TestResults.Trigger trigger, ReadyGate gate);

    /**
     * Cancels the run in flight and drops the run waiting to start; returns once its processes are gone. Until the
     * next {@link #changed} or {@link #release}, a request is queued but not started: the application is being
     * recompiled, and a test run's Maven must not compile into the same {@code target/classes} meanwhile.
     */
    void interrupt();

    /** Ends the hold of {@link #interrupt} without a new run, such as after a recompile that failed. */
    void release();

    /** What a run waits for before starting, such as the end of an application reload. */
    @FunctionalInterface
    interface ReadyGate {

        /** Open at once. */
        ReadyGate NOW = abandoned -> true;

        /**
         * Blocks until the run may start.
         *
         * @param abandoned {@code true} once a newer change or the goal's end made the wait pointless
         * @return {@code true} to run, {@code false} to give up
         */
        boolean await(BooleanSupplier abandoned) throws InterruptedException;
    }
}
