package io.vidocq.dirac.api;

/**
 * Exception racine de Dirac — levée lors d'une erreur de configuration ou d'enregistrement
 * de métriques (signature incompatible pour {@code @Gauge}, {@code MetricID} dupliqué avec
 * type différent, etc.).
 */
public class DiracException extends RuntimeException {

    public DiracException(String message) {
        super(message);
    }

    public DiracException(String message, Throwable cause) {
        super(message, cause);
    }
}
