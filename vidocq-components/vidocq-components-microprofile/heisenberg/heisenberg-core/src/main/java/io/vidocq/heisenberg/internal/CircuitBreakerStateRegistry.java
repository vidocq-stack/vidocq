package io.vidocq.heisenberg.internal;

/**
 * Registre d'état du Circuit Breaker — interface pour isoler la gestion d'état.
 *
 * <p>Implémentation produite dans heisenberg-cdi-vauban {@link io.vidocq.heisenberg.cdi.internal.StateRegistry}.</p>
 */
public interface CircuitBreakerStateRegistry {

    /**
     * Récupère l'état actuel du circuit breaker pour une méthode donnée.
     */
    CircuitBreakerState getState(String beanClass, String methodName);

    /**
     * Enregistre un succès en état HALF_OPEN.
     */
    void recordSuccess(String beanClass, String methodName);

    /**
     * Enregistre un échec (incrémente le compteur d'erreurs).
     */
    void recordFailure(String beanClass, String methodName);

    /**
     * Force le circuit à l'état OPEN (transition manuelle pour tests).
     */
    void setOpen(String beanClass, String methodName);

    /**
     * Force le circuit à l'état CLOSED (réinitialization).
     */
    void setClosed(String beanClass, String methodName);

    /**
     * Force le circuit à l'état HALF_OPEN.
     */
    void setHalfOpen(String beanClass, String methodName);

    /**
     * Retourne le nombre de millisecondes écoulées depuis l'ouverture du circuit.
     */
    long getMillisSinceOpen(String beanClass, String methodName);

    /**
     * Retourne le nombre de succès enregistrés en HALF_OPEN.
     */
    int getSuccessesInHalfOpen(String beanClass, String methodName);
}

