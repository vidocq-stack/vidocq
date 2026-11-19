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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * {@code @Timeout} engine using virtual threads (Project Loom, finalized in Java 21+).
 *
 * <p>A synchronous invocation runs on the caller's thread, so it keeps the caller's context: thread locals such as
 * the OpenTelemetry context, scoped values, the request context (BUG-015). A watchdog virtual thread
 * interrupts the caller at the deadline (best effort); the interruption is cleared before
 * {@code TimeoutException} is thrown, so it never outlives the invocation. An asynchronous invocation, whose caller
 * has already returned, is forked into an ephemeral virtual thread joined until the deadline. No platform-thread
 * pool is created. MP FT 4.1 §4.</p>
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
        if (!asyncCall) {
            return onCallerThread(invocation, config.duration());
        }
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

            // Async mode only (a synchronous invocation runs on the caller's thread, above): control is
            // returned at once, the asynchronous virtual thread already carries the wait.
            throw new TimeoutException(
                    "Invocation timed out after " + timeout.toMillis() + " ms"
            );
        }

        // The thread completed in time: Thread.join() provides happens-before.
        Throwable error = errorRef.get();
        if (error != null) {
            throw Failures.asException(error);
        }

        return resultRef.get();
    }

    private static final int RUNNING = 0;
    private static final int DONE = 1;
    private static final int TIMED_OUT = 2;

    /**
     * Runs {@code invocation} on the caller's thread, which a watchdog interrupts at the deadline. §4.1.2: the caller
     * waits for the method to end, then gets {@code TimeoutException} if the deadline passed first, whatever the method
     * returned.
     */
    private static Object onCallerThread(PolicyComposer.Invocation invocation, Duration timeout) throws Exception {
        Thread caller = Thread.currentThread();
        AtomicInteger state = new AtomicInteger(RUNNING);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().name("heisenberg-timeout").start(() -> {
            try {
                Thread.sleep(timeout);
                if (state.compareAndSet(RUNNING, TIMED_OUT)) {
                    caller.interrupt();
                    interrupted.set(true);
                }
            } catch (InterruptedException _) {
                // The invocation ended first and stopped the watch; this thread ends here, interrupted as it was.
                Thread.currentThread().interrupt();
            }
        });
        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable t) {
            if (timedOut(state, interrupted, watchdog)) {
                throw timeoutException(timeout);
            }
            throw Failures.asException(t);
        }
        if (timedOut(state, interrupted, watchdog)) {
            throw timeoutException(timeout);
        }
        return result;
    }

    /**
     * Ends the watch: {@code false} when the invocation ended first; {@code true} when the deadline passed first, once
     * the caller's interruption is cleared.
     */
    private static boolean timedOut(AtomicInteger state, AtomicBoolean interrupted, Thread watchdog) {
        if (state.compareAndSet(RUNNING, DONE)) {
            watchdog.interrupt();
            return false;
        }
        // The deadline passed: the watchdog is between its state change and the interrupt it sends this thread, a
        // few instructions. Wait for the interrupt to be sent, then clear it.
        while (!interrupted.get()) {
            Thread.onSpinWait();
        }
        Thread.interrupted();
        return true;
    }

    private static TimeoutException timeoutException(Duration timeout) {
        return new TimeoutException("Invocation timed out after " + timeout.toMillis() + " ms");
    }
}
