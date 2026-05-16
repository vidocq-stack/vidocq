package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Configuration immuable pour la politique {@code @CircuitBreaker}.
 *
 * <p>MP FT 4.1 §5 : circuit breaker avec fenêtre glissante count-based.</p>
 *
 * @param requestVolumeThreshold nombre minimum de requêtes pour déclencher le calcul de ratio (défaut 20)
 * @param failureRatio           seuil de ratio d'échecs : failure_count / request_count (défaut 0.5)
 * @param delay                  délai avant transition OPEN → HALF_OPEN (défaut 5s)
 * @param delayUnit              unité du délai (défaut SECONDS)
 * @param successThreshold       nombre de succès requis en HALF_OPEN pour fermer (défaut 1)
 * @param failOn                 exceptions qui incrémentent les échecs (défaut Throwable.class)
 * @param skipOn                 exceptions ignorées (a priorité sur failOn)
 */
public record CircuitBreakerConfig(
        int requestVolumeThreshold,
        double failureRatio,
        long delay,
        ChronoUnit delayUnit,
        int successThreshold,
        Class<? extends Throwable>[] failOn,
        Class<? extends Throwable>[] skipOn
) {

    /** Constantes défaut (spec MP FT 4.1 §5). */
    public static final CircuitBreakerConfig DEFAULT = new CircuitBreakerConfig(
            20, 0.5, 5, ChronoUnit.SECONDS, 1,
            new Class[]{Throwable.class}, new Class[0]
    );

    public CircuitBreakerConfig {
        if (requestVolumeThreshold <= 0) {
            throw new IllegalArgumentException("requestVolumeThreshold must be > 0");
        }
        if (failureRatio <= 0.0 || failureRatio > 1.0) {
            throw new IllegalArgumentException("failureRatio must be in (0, 1]");
        }
        if (delay <= 0) {
            throw new IllegalArgumentException("delay must be > 0");
        }
        if (successThreshold <= 0) {
            throw new IllegalArgumentException("successThreshold must be > 0");
        }
        failOn = failOn == null ? new Class[]{Throwable.class} : failOn.clone();
        skipOn = skipOn == null ? new Class[0] : skipOn.clone();
    }

    /** Convertit le délai en {@link Duration}. */
    public Duration delayDuration() {
        return Duration.of(delay, delayUnit);
    }
}

