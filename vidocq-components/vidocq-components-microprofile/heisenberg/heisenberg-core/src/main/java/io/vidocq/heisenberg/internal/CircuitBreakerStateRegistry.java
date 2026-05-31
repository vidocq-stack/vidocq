package io.vidocq.heisenberg.internal;

/**
 * Circuit Breaker state registry — interface for isolating state management.
 *
 * <p>Implementation produced in heisenberg-cdi-vauban {@link io.vidocq.heisenberg.cdi.internal.StateRegistry}.</p>
 */
public interface CircuitBreakerStateRegistry {

    /**
     * Returns the current circuit-breaker state for a given method.
     */
    CircuitBreakerState getState(String beanClass, String methodName);

    /**
     * Records a success in HALF_OPEN state.
     */
    void recordSuccess(String beanClass, String methodName);

    /**
     * Records a failure (increments the error counter).
     */
    void recordFailure(String beanClass, String methodName);

    /**
     * Forces the circuit into OPEN state (manual transition for tests).
     */
    void setOpen(String beanClass, String methodName);

    /**
     * Forces the circuit into CLOSED state (reset).
     */
    void setClosed(String beanClass, String methodName);

    /**
     * Forces the circuit into HALF_OPEN state.
     */
    void setHalfOpen(String beanClass, String methodName);

    /**
     * Returns the number of milliseconds elapsed since the circuit opened.
     */
    long getMillisSinceOpen(String beanClass, String methodName);

    /**
     * Returns the number of successes recorded in HALF_OPEN.
     */
    int getSuccessesInHalfOpen(String beanClass, String methodName);
}

