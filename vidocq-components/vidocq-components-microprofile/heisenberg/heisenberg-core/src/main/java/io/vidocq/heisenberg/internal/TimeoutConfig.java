package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Configuration immuable pour la politique {@code @Timeout}.
 *
 * <p>MP FT 4.1 §4 : le timeout s'applique à chaque tentative individuelle.</p>
 *
 * @param value durée du timeout (doit être &gt; 0)
 * @param unit  unité ChronoUnit (défaut spec : MILLIS)
 */
public record TimeoutConfig(long value, ChronoUnit unit) {

    /** Valeur par défaut de la spec : 1 000 ms. */
    public static final TimeoutConfig DEFAULT = new TimeoutConfig(1_000L, ChronoUnit.MILLIS);

    public TimeoutConfig {
        if (value <= 0) {
            throw new IllegalArgumentException("timeout value must be > 0, got: " + value);
        }
    }

    /** Convertit la configuration en {@link Duration}. */
    public Duration duration() {
        return Duration.of(value, unit);
    }
}

