package io.vidocq.humboldt.sdk.common;

import java.time.Instant;

/**
 * Source de temps utilisée par le SDK Humboldt pour horodater spans, metrics et logs.
 *
 * <p>Deux horloges distinctes :</p>
 * <ul>
 *   <li>{@link #now()} — horloge wall clock en nanosecondes depuis epoch UTC,
 *       utilisée pour les timestamps de spans/metrics/logs publiés ;</li>
 *   <li>{@link #nanoTime()} — horloge monotone en nanosecondes, utilisée pour
 *       mesurer les durées (immune au saut d'heure NTP).</li>
 * </ul>
 */
public interface Clock {

    /**
     * @return l'horloge système par défaut.
     */
    static Clock system() {
        return SystemClock.INSTANCE;
    }

    /**
     * @return l'instant courant en nanosecondes depuis epoch UTC.
     */
    long now();

    /**
     * @return un compteur monotone en nanosecondes, voir {@link System#nanoTime()}.
     */
    long nanoTime();

    final class SystemClock implements Clock {

        static final SystemClock INSTANCE = new SystemClock();

        @Override
        public long now() {
            Instant n = Instant.now();
            return Math.multiplyExact(n.getEpochSecond(), 1_000_000_000L) + n.getNano();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }
}
