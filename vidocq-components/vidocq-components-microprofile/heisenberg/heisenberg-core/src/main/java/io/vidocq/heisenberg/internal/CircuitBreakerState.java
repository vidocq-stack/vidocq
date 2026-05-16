package io.vidocq.heisenberg.internal;

/**
 * États du Circuit Breaker — MP FT 4.1 §5.
 */
public enum CircuitBreakerState {
    /**
     * État normal — les invocations procèdent.
     */
    CLOSED,

    /**
     * État d'erreur — les invocations lever immédiatement {@link org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException}.
     */
    OPEN,

    /**
     * État test après le délai — quelques invocations passent pour tester la récupération.
     */
    HALF_OPEN
}

