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

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import java.lang.reflect.Method;
import java.util.concurrent.Semaphore;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;

/**
 * {@code @Bulkhead} engine — synchronous mode.
 *
 * <p>Uses a {@link Semaphore} with fairness=true to limit concurrency.
 * Virtual threads without a platform-thread pool. MP FT 4.1 §7.</p>
 */
public final class BulkheadEngine {

    private final BulkheadConfig config;
    private final BulkheadStateRegistry registry;

    public BulkheadEngine(BulkheadConfig config, BulkheadStateRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    /**
     * Executes the invocation with bulkhead protection (synchronous mode).
     *
     * @param invocation   the invocation to protect
     * @param beanClass    bean class
     * @param methodName   method name
     * @return invocation result
     * @throws BulkheadException if the bulkhead is saturated (synchronous fail-fast mode)
     * @throws Exception         if the invocation fails
     */
    public Object execute(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        return execute(invocation, beanClass, methodName, FtMetricsRecorder.NOOP, null, null);
    }

    public Object execute(PolicyComposer.Invocation invocation, String beanClass, String methodName,
                          FtMetricsRecorder recorder, Class<?> runtimeBeanClass, Method method) throws Exception {
        Semaphore semaphore = registry.getSemaphore(beanClass, methodName, config.value());

        // Synchronous mode: immediate try-acquire (non-blocking)
        if (!semaphore.tryAcquire()) {
            recorder.recordBulkheadRejected(runtimeBeanClass, method);
            throw new BulkheadException(
                    "Bulkhead saturated for " + beanClass + "#" + methodName +
                            " (max concurrent: " + config.value() + ")"
            );
        }

        recorder.bulkheadRunningDelta(runtimeBeanClass, method, +1);
        long runStart = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            semaphore.release();
            recorder.bulkheadRunningDelta(runtimeBeanClass, method, -1);
            recorder.recordBulkheadAccepted(runtimeBeanClass, method, 0, System.nanoTime() - runStart);
        }
    }

    /**
     * Executes the invocation in async mode.
     *
     * <p>In async mode, a bounded wait queue is used if {@code waitingTaskQueue > 0}.
     * The method remains synchronous on the engine side, but it is executed in a virtual thread
     * by {@link PolicyComposer} when {@code @Asynchronous} is present.</p>
     */
    public Object executeAsync(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        return executeAsync(invocation, beanClass, methodName, FtMetricsRecorder.NOOP, null, null);
    }

    public Object executeAsync(PolicyComposer.Invocation invocation, String beanClass, String methodName,
                               FtMetricsRecorder recorder, Class<?> runtimeBeanClass, Method method) throws Exception {
        BulkheadStateRegistry.BulkheadState state = registry.getAsyncState(beanClass, methodName, config.value(), config.waitingTaskQueue());
        Semaphore permits = state.permits();

        // Fast-path: execute immediately when a permit is available.
        if (permits.tryAcquire()) {
            recorder.bulkheadRunningDelta(runtimeBeanClass, method, +1);
            long runStart = System.nanoTime();
            try {
                return invocation.proceed();
            } finally {
                permits.release();
                recorder.bulkheadRunningDelta(runtimeBeanClass, method, -1);
                recorder.recordBulkheadAccepted(runtimeBeanClass, method, 0, System.nanoTime() - runStart);
            }
        }

        // No permit left: enqueue while waitingTaskQueue has capacity.
        if (config.waitingTaskQueue() <= 0 || !state.waitingQueue().tryAcquire()) {
            recorder.recordBulkheadRejected(runtimeBeanClass, method);
            throw new BulkheadException(
                    "Bulkhead async queue saturated for " + beanClass + "#" + methodName +
                            " (waitingTaskQueue: " + config.waitingTaskQueue() + ")"
            );
        }

        recorder.bulkheadWaitingDelta(runtimeBeanClass, method, +1);
        long waitStart = System.nanoTime();
        try {
            permits.acquire();
        } finally {
            // Once we got a permit (or got interrupted), we are no longer in queue.
            state.waitingQueue().release();
            recorder.bulkheadWaitingDelta(runtimeBeanClass, method, -1);
        }
        long waitNanos = System.nanoTime() - waitStart;

        recorder.bulkheadRunningDelta(runtimeBeanClass, method, +1);
        long runStart = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            permits.release();
            recorder.bulkheadRunningDelta(runtimeBeanClass, method, -1);
            recorder.recordBulkheadAccepted(runtimeBeanClass, method, waitNanos, System.nanoTime() - runStart);
        }
    }
}

