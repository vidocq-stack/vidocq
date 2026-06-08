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
package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * {@code @Timeout} engine using virtual threads (Project Loom, finalized in Java 21+).
 *
 * <p>The invocation is forked into a dedicated virtual thread via {@code Thread.ofVirtual()}.
 * {@code Thread.join(Duration)} waits until the deadline; if the thread is still active,
 * it is interrupted (best-effort) and an MP FT {@code TimeoutException} is thrown.
 * No platform-thread pool is created: each invocation forks an ephemeral virtual thread.
 * MP FT 4.1 §4.</p>
 *
 * <p><strong>Note:</strong> The target implementation remains {@code StructuredTaskScope}
 * (JEP 505), which guarantees cancellation of child threads when the scope is closed. This
 * implementation will migrate as soon as the API leaves the preview period (Java 26+).</p>
 */
public final class TimeoutEngine {

    private TimeoutEngine() {}

    /**
     * Executes the invocation with a timeout.
     *
     * @param invocation the invocation to protect
     * @param config     the timeout configuration
     * @return the invocation result
     * @throws TimeoutException if the deadline is exceeded (MP FT {@code TimeoutException})
     * @throws Exception        if the invocation throws an exception before the deadline
     */
    public static Object execute(PolicyComposer.Invocation invocation, TimeoutConfig config) throws Exception {
        return execute(invocation, config, false);
    }

    /**
     * Executes the invocation with a timeout.
     *
     * @param invocation     the invocation to protect
     * @param config         the timeout configuration
     * @param asyncCall      {@code true} if called from the async wrapper (the caller
     *                       has already returned, so it must not be blocked past the
     *                       deadline). {@code false} = sync: §4.1.2 requires waiting for
     *                       the method's actual completion (uninterruptible) before throwing
     *                       the {@link TimeoutException}.
     */
    public static Object execute(PolicyComposer.Invocation invocation, TimeoutConfig config, boolean asyncCall) throws Exception {
        Duration timeout = config.duration();
        AtomicReference<Object> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Thread vThread = Thread.ofVirtual()
                .name("heisenberg-timeout")
                .start(() -> {
                    try {
                        resultRef.set(invocation.proceed());
                    } catch (Throwable t) {
                        errorRef.set(t);
                    }
                });

        boolean completed = vThread.join(timeout);

        if (!completed) {
            // The deadline has passed: interrupt the virtual thread (best effort).
            // Interruptible blocking operations (Thread.sleep, NIO I/O) will be cancelled.
            vThread.interrupt();

            // §4.1.2 (sync mode only): wait for the method to actually finish before
            // propagating TimeoutException. For uninterruptible methods, the caller remains
            // blocked until the real return. In async mode, control is returned immediately
            // (the asynchronous virtual thread already carries the wait).
            if (!asyncCall) {
                vThread.join();
            }

            throw new TimeoutException(
                    "Invocation timed out after " + timeout.toMillis() + " ms"
            );
        }

        // The thread completed in time: Thread.join() provides happens-before.
        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof Exception exception) {
                throw exception;
            }
            if (error instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(error);
        }

        return resultRef.get();
    }
}
