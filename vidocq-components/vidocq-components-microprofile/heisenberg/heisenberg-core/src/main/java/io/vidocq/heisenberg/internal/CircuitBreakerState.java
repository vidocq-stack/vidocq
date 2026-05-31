package io.vidocq.heisenberg.internal;

/**
 * Circuit Breaker states — MP FT 4.1 §5.
 */
public enum CircuitBreakerState {
    /**
     * Normal state — invocations proceed.
     */
    CLOSED,

    /**
     * Error state — invocations immediately throw {@link org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException}.
     */
    OPEN,

    /**
     * Test state after the delay — a few invocations are allowed through to test recovery.
     */
    HALF_OPEN
}

