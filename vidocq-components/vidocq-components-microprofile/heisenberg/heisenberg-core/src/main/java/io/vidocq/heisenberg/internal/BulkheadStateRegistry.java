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

import java.util.concurrent.Semaphore;

/**
 * Bulkhead state registry — interface for managing shared semaphores.
 *
 * <p>Implementation produced in heisenberg-cdi-vauban.</p>
 */
public interface BulkheadStateRegistry {

    /**
     * Shared state for async mode: concurrency semaphore + wait-queue semaphore.
     */
    record BulkheadState(Semaphore permits, Semaphore waitingQueue) {}

    /**
     * Gets or creates the semaphore for a given bulkhead.
     *
     * @param beanClass   bean class
     * @param methodName  method name
     * @param permits     maximum number of acquires (capacity)
     * @return shared semaphore for this method
     */
    Semaphore getSemaphore(String beanClass, String methodName, int permits);

    /**
     * Gets or creates the semaphores for async mode.
     *
     * @param beanClass       bean class
     * @param methodName      method name
     * @param permits         maximum number of concurrent invocations
     * @param waitingTaskQueue maximum wait-queue size
     * @return shared state for the async bulkhead
     */
    default BulkheadState getAsyncState(String beanClass, String methodName, int permits, int waitingTaskQueue) {
        return new BulkheadState(getSemaphore(beanClass, methodName, permits), new Semaphore(waitingTaskQueue, true));
    }
}

