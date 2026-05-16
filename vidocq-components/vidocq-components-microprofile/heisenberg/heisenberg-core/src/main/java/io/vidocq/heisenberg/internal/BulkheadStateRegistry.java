package io.vidocq.heisenberg.internal;

import java.util.concurrent.Semaphore;

/**
 * Registre d'état du Bulkhead — interface pour gérer les sémaphores partagés.
 *
 * <p>Implémentation produite dans heisenberg-cdi-vauban.</p>
 */
public interface BulkheadStateRegistry {

    /**
     * État partagé pour le mode async : sémaphore de concurrence + sémaphore de file d'attente.
     */
    record BulkheadState(Semaphore permits, Semaphore waitingQueue) {}

    /**
     * Récupère ou crée le sémaphore pour un bulkhead donné.
     *
     * @param beanClass   classe du bean
     * @param methodName  nom de la méthode
     * @param permits     nombre max d'acquires (capacité)
     * @return sémaphore partagé pour cette méthode
     */
    Semaphore getSemaphore(String beanClass, String methodName, int permits);

    /**
     * Récupère ou crée les sémaphores pour le mode async.
     *
     * @param beanClass       classe du bean
     * @param methodName      nom de la méthode
     * @param permits         nombre max d'invocations concurrentes
     * @param waitingTaskQueue taille max de la file d'attente
     * @return état partagé pour le bulkhead async
     */
    default BulkheadState getAsyncState(String beanClass, String methodName, int permits, int waitingTaskQueue) {
        return new BulkheadState(getSemaphore(beanClass, methodName, permits), new Semaphore(waitingTaskQueue, true));
    }
}

