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
package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.BulkheadStateRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Global Bulkhead state registry — CDI {@code @ApplicationScoped} bean.
 *
 * <p>Stores the shared semaphores for all bulkheads
 * using a stable key: {@code ClassName#methodName}.</p>
 */
@ApplicationScoped
public class BulkheadStateRegistryBean implements BulkheadStateRegistry {

    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> waitingQueues = new ConcurrentHashMap<>();

    /**
     * Stable key for a bulkhead.
     */
    private String key(String beanClass, String methodName) {
        return beanClass + "#" + methodName;
    }

    @Override
    public Semaphore getSemaphore(String beanClass, String methodName, int permits) {
        String k = key(beanClass, methodName);
        return semaphores.computeIfAbsent(k, ignored -> new Semaphore(permits, true));
    }

    @Override
    public BulkheadStateRegistry.BulkheadState getAsyncState(String beanClass, String methodName, int permits, int waitingTaskQueue) {
        String k = key(beanClass, methodName);
        Semaphore semaphore = semaphores.computeIfAbsent(k, ignored -> new Semaphore(permits, true));
        Semaphore waitingQueue = waitingQueues.computeIfAbsent(k, ignored -> new Semaphore(waitingTaskQueue, true));
        return new BulkheadStateRegistry.BulkheadState(semaphore, waitingQueue);
    }
}

