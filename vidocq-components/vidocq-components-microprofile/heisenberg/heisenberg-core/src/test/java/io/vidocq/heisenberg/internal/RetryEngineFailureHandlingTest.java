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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** MP FT 4.1 §3: how {@link RetryEngine} treats each kind of failure, and what it reports. */
class RetryEngineFailureHandlingTest {

    private static final RetryConfig RETRY_EXCEPTIONS = config(2, 0, 10_000, new Class[]{Exception.class});

    @SuppressWarnings("unchecked")
    private static RetryConfig config(int maxRetries, long delayMillis, long maxDurationMillis, Class<?>[] retryOn) {
        return new RetryConfig(maxRetries, delayMillis, ChronoUnit.MILLIS, maxDurationMillis, ChronoUnit.MILLIS,
                0, ChronoUnit.MILLIS, (Class<? extends Throwable>[]) retryOn, new Class[0]);
    }

    /**
     * Records every {@code recordRetry} call; the other events go to {@link FtMetricsRecorder#NOOP}
     * through a dynamic proxy, since the retry engine reports nothing else.
     */
    private static final class RetryRecorder {
        final List<FtMetricsRecorder.RetryResult> results = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        final FtMetricsRecorder recorder = (FtMetricsRecorder) Proxy.newProxyInstance(
                FtMetricsRecorder.class.getClassLoader(), new Class<?>[]{FtMetricsRecorder.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("recordRetry")) {
                        counts.add((Integer) args[2]);
                        results.add((FtMetricsRecorder.RetryResult) args[3]);
                        return null;
                    }
                    return method.invoke(FtMetricsRecorder.NOOP, args);
                });
    }

    /** Throws a checked {@link Throwable} that an {@code Invocation} cannot declare. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> Object sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    void reportsTheRetryCountAndTheMaxRetriesOutcome() {
        RetryRecorder recorder = new RetryRecorder();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IOException.class, () -> RetryEngine.execute(() -> {
            calls.incrementAndGet();
            throw new IOException("down");
        }, RETRY_EXCEPTIONS, recorder.recorder, RetryEngineFailureHandlingTest.class, null));

        assertEquals(3, calls.get());
        assertEquals(List.of(FtMetricsRecorder.RetryResult.MAX_RETRIES_REACHED), recorder.results);
        assertEquals(List.of(2), recorder.counts);
    }

    @Test
    void reportsANonRetryableFailure() {
        RetryRecorder recorder = new RetryRecorder();
        RetryConfig ioOnly = config(3, 0, 10_000, new Class[]{IOException.class});

        assertThrows(IllegalStateException.class, () -> RetryEngine.execute(() -> {
            throw new IllegalStateException("fatal");
        }, ioOnly, recorder.recorder, null, null));

        assertEquals(List.of(FtMetricsRecorder.RetryResult.EXCEPTION_NOT_RETRYABLE), recorder.results);
    }

    @Test
    void zeroMaxDurationStopsAfterTheFirstAttempt() {
        RetryRecorder recorder = new RetryRecorder();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IOException.class, () -> RetryEngine.execute(() -> {
            calls.incrementAndGet();
            throw new IOException("slow");
        }, config(5, 0, 0, new Class[]{Exception.class}), recorder.recorder, null, null));

        assertEquals(1, calls.get());
        assertEquals(List.of(FtMetricsRecorder.RetryResult.MAX_DURATION_REACHED), recorder.results);
    }

    @Test
    void invocationTargetExceptionIsUnwrappedBeforeMatching() {
        IOException cause = new IOException("wrapped");

        IOException thrown = assertThrows(IOException.class, () -> RetryEngine.execute(() -> {
            throw new InvocationTargetException(cause);
        }, config(0, 0, 10_000, new Class[]{IOException.class})));

        assertSame(cause, thrown);
    }

    @Test
    void executionExceptionIsUnwrappedBeforeMatching() {
        IOException cause = new IOException("async");

        IOException thrown = assertThrows(IOException.class, () -> RetryEngine.execute(() -> {
            throw new ExecutionException(cause);
        }, config(0, 0, 10_000, new Class[]{IOException.class})));

        assertSame(cause, thrown);
    }

    @Test
    void errorsAreRethrownAsIs() {
        AssertionError error = new AssertionError("broken");
        RetryConfig retryThrowable = config(0, 0, 10_000, new Class[]{Throwable.class});

        AssertionError thrown = assertThrows(AssertionError.class, () -> RetryEngine.execute(() -> {
            throw error;
        }, retryThrowable));

        assertSame(error, thrown);
    }

    @Test
    void plainThrowablesAreWrappedInARuntimeException() {
        Throwable failure = new Throwable("odd");
        RetryConfig retryThrowable = config(0, 0, 10_000, new Class[]{Throwable.class});

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RetryEngine.execute(() -> sneakyThrow(failure), retryThrowable));

        assertSame(failure, thrown.getCause());
    }

    @Test
    void interruptionIsNeverRetriedAndKeepsTheInterruptFlag() {
        RetryConfig retryThrowable = config(3, 0, 10_000, new Class[]{Throwable.class});

        boolean retried = RetryEngine.shouldRetry(new InterruptedException("stop"), retryThrowable);

        assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
        assertFalse(retried);
    }

    @Test
    void cancellationIsNeverRetried() {
        RetryConfig retryThrowable = config(3, 0, 10_000, new Class[]{Throwable.class});

        assertFalse(RetryEngine.shouldRetry(new CancellationException("cancelled"), retryThrowable));
    }

    @Test
    void interruptedBackoffStopsTheRetries() {
        AtomicInteger calls = new AtomicInteger();
        RetryConfig withDelay = config(3, 5_000, 60_000, new Class[]{Exception.class});

        assertThrows(InterruptedException.class, () -> RetryEngine.execute(() -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new IOException("then sleep");
        }, withDelay));

        assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
        assertEquals(1, calls.get());
    }
}
