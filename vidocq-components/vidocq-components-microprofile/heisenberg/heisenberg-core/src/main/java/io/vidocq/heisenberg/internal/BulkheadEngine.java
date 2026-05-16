package io.vidocq.heisenberg.internal;

import java.util.concurrent.Semaphore;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;

/**
 * Moteur {@code @Bulkhead} — mode synchrone.
 *
 * <p>Utilise un {@link Semaphore} avec fairness=true pour limiter la concurrence.
 * Virtual threads sans pool platform. MP FT 4.1 §7.</p>
 */
public final class BulkheadEngine {

    private final BulkheadConfig config;
    private final BulkheadStateRegistry registry;

    public BulkheadEngine(BulkheadConfig config, BulkheadStateRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    /**
     * Exécute l'invocation avec protection du bulkhead (mode synchrone).
     *
     * @param invocation   l'invocation à protéger
     * @param beanClass    classe du bean
     * @param methodName   nom de la méthode
     * @return résultat de l'invocation
     * @throws BulkheadException si le bulkhead est saturé (mode synchrone fail-fast)
     * @throws Exception         si l'invocation échoue
     */
    public Object execute(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        Semaphore semaphore = registry.getSemaphore(beanClass, methodName, config.value());

        // Mode synchrone : try-acquire immédiat (non-bloquant)
        if (!semaphore.tryAcquire()) {
            throw new BulkheadException(
                    "Bulkhead saturated for " + beanClass + "#" + methodName +
                            " (max concurrent: " + config.value() + ")"
            );
        }

        try {
            return invocation.proceed();
        } finally {
            semaphore.release();
        }
    }

    /**
     * Exécute l'invocation en mode async.
     *
     * <p>En mode async, une file d'attente bornée est utilisée si {@code waitingTaskQueue > 0}.
     * La méthode reste synchronique côté moteur mais elle est exécutée dans un virtual thread
     * par {@link PolicyComposer} quand {@code @Asynchronous} est présent.</p>
     */
    public Object executeAsync(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        BulkheadStateRegistry.BulkheadState state = registry.getAsyncState(beanClass, methodName, config.value(), config.waitingTaskQueue());
        Semaphore permits = state.permits();

        // Fast-path: execute immediately when a permit is available.
        if (permits.tryAcquire()) {
            try {
                return invocation.proceed();
            } finally {
                permits.release();
            }
        }

        // No permit left: enqueue while waitingTaskQueue has capacity.
        if (config.waitingTaskQueue() <= 0 || !state.waitingQueue().tryAcquire()) {
            throw new BulkheadException(
                    "Bulkhead async queue saturated for " + beanClass + "#" + methodName +
                            " (waitingTaskQueue: " + config.waitingTaskQueue() + ")"
            );
        }

        try {
            permits.acquire();
        } finally {
            // Once we got a permit (or got interrupted), we are no longer in queue.
            state.waitingQueue().release();
        }

        try {
            return invocation.proceed();
        } finally {
            permits.release();
        }
    }
}

