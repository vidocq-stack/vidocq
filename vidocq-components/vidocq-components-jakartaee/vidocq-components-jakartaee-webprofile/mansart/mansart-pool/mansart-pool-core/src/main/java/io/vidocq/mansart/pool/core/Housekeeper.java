package io.vidocq.mansart.pool.core;

import io.vidocq.mansart.pool.PoolConfig;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.locks.LockSupport;

/**
 * Single virtual-thread loop that periodically scans the idle deque for connections past their
 * {@link PoolConfig#idleTimeout()} or {@link PoolConfig#maxLifetime()}, closes them, and lets the
 * pool create fresh ones on demand.
 *
 * <p>Runs on a virtual thread because the loop spends almost all its time parked. Sweep period is
 * {@code idleTimeout / 4} so an expired connection is reclaimed within roughly its own idle window
 * (good enough — we are not a watchdog).
 *
 * <p>Failures inside {@link ConnectionPool#sweep} are logged at WARNING and never propagate; a
 * crashed housekeeper would leak idle connections silently which is far worse than a swallowed log.
 */
final class Housekeeper {

    private static final Logger LOG = System.getLogger("io.vidocq.mansart.pool");

    private final ConnectionPool pool;
    private final long sweepIntervalNanos;
    private final Thread thread;
    private volatile boolean stopped;

    Housekeeper(ConnectionPool pool, PoolConfig config) {
        this.pool = pool;
        // idleTimeout / 4 is a deliberate compromise: tight enough that expired connections
        // are reclaimed within roughly their own idle window, loose enough that we don't burn
        // CPU on a 1ms loop when idleTimeout is in minutes. Floor at 1s so a misconfigured
        // sub-second idleTimeout doesn't spin.
        this.sweepIntervalNanos = Math.max(
                java.time.Duration.ofSeconds(1).toNanos(),
                config.idleTimeout().toNanos() / 4);
        this.thread = Thread.ofVirtual()
                .name("mansart-pool-housekeeper")
                .unstarted(this::loop);
    }

    void start() {
        thread.start();
    }

    void stop() {
        stopped = true;
        thread.interrupt();
    }

    private void loop() {
        while (!stopped) {
            LockSupport.parkNanos(sweepIntervalNanos);
            if (stopped) return;
            try {
                pool.sweep(System.nanoTime());
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "housekeeper sweep failed", t);
                // intentional: do not propagate, keep the loop alive
            }
        }
    }
}
