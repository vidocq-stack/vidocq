package io.vidocq.heisenberg.api;

/**
 * Base Heisenberg exception — wraps configuration errors detected at container
 * startup (missing fallback method, return type incompatible with
 * {@code @Asynchronous}, etc.).
 *
 * <p>Distinct from MP FT exceptions raised at execution time
 * ({@code TimeoutException}, {@code CircuitBreakerOpenException}, etc.)
 * which are defined by the spec.</p>
 */
public class FaultToleranceException extends RuntimeException {

    public FaultToleranceException(String message) {
        super(message);
    }

    public FaultToleranceException(String message, Throwable cause) {
        super(message, cause);
    }
}
