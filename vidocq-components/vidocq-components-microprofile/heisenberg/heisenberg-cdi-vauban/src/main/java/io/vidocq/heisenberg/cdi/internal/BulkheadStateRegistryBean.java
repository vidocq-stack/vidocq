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

