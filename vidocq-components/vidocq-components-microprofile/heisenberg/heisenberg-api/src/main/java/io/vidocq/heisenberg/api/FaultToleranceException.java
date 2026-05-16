package io.vidocq.heisenberg.api;

/**
 * Exception de base de Heisenberg — enveloppe les erreurs de configuration
 * détectées au démarrage du container (méthode fallback inexistante, type de
 * retour incompatible avec {@code @Asynchronous}, etc.).
 *
 * <p>Distincte des exceptions MP FT levées à l'exécution
 * ({@code TimeoutException}, {@code CircuitBreakerOpenException}, etc.)
 * qui sont définies dans la spec.</p>
 */
public class FaultToleranceException extends RuntimeException {

    public FaultToleranceException(String message) {
        super(message);
    }

    public FaultToleranceException(String message, Throwable cause) {
        super(message, cause);
    }
}
