/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
        // CPU on a 1ms loop when idleTimeout is in minutes. When leak detection is enabled,
        // tighten the sweep to leakDetectionThreshold/4 so a leak surfaces in roughly its own
        // grace window. Floor at 50ms so a misconfigured sub-100ms threshold (mainly tests)
        // doesn't spin too hot.
        long base = config.idleTimeout().toNanos() / 4;
        long leak = config.leakDetectionThreshold().isZero()
                ? Long.MAX_VALUE
                : config.leakDetectionThreshold().toNanos() / 4;
        long minFloor = pool.leakDetectionEnabled()
                ? java.time.Duration.ofMillis(50).toNanos()
                : java.time.Duration.ofSeconds(1).toNanos();
        this.sweepIntervalNanos = Math.max(minFloor, Math.min(base, leak));
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
            long now = System.nanoTime();
            try {
                pool.sweep(now);
                pool.detectLeaks(now);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "housekeeper sweep failed", t);
                // intentional: do not propagate, keep the loop alive
            }
        }
    }
}
