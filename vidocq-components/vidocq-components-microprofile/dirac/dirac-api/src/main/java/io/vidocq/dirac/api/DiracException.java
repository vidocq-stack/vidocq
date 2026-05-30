package io.vidocq.dirac.api;

/**
 * Root Dirac exception — thrown when there is a configuration or metric registration error
 * (incompatible signature for {@code @Gauge}, duplicate {@code MetricID} with
 * a different type, etc.).
 */
public class DiracException extends RuntimeException {

    public DiracException(String message) {
        super(message);
    }

    public DiracException(String message, Throwable cause) {
        super(message, cause);
    }
}
