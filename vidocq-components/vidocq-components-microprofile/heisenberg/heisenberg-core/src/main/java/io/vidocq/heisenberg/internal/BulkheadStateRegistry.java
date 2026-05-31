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

