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
package io.vidocq.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MP-C-1 — exercises {@link PoolConfig#leakDetectionThreshold()} end-to-end. Validation goes
 * through the {@code totalLeaks} counter rather than scraping {@link System.Logger} output:
 * the counter is what production monitoring will read anyway, and it is reliable across logger
 * configurations.
 */
class LeakDetectionTest {

    private MansartDataSource ds;

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void leakIsCountedWhenConnectionHeldPastThreshold() throws Exception {
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .leakDetectionThreshold(Duration.ofMillis(150))
                .build());

        Connection leaked = ds.getConnection();
        try {
            // Wait long enough for at least one housekeeper sweep AFTER the threshold elapsed.
            // Sweep cadence is leakDetectionThreshold/4 (~38ms), capped to a 50ms floor.
            waitFor(() -> ds.snapshot().totalLeaks() >= 1L, Duration.ofSeconds(2));
            assertThat(ds.snapshot().totalLeaks()).isGreaterThanOrEqualTo(1L);
        } finally {
            leaked.close();
        }
    }

    @Test
    void leakWarningIsEmittedAtMostOncePerBorrow() throws Exception {
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .leakDetectionThreshold(Duration.ofMillis(150))
                .build());

        Connection leaked = ds.getConnection();
        try {
            waitFor(() -> ds.snapshot().totalLeaks() >= 1L, Duration.ofSeconds(2));
            long firstCount = ds.snapshot().totalLeaks();
            // Let several more sweeps run.
            Thread.sleep(500);
            assertThat(ds.snapshot().totalLeaks())
                    .as("leak counter must not advance on repeated sweeps over the same leaker")
                    .isEqualTo(firstCount);
        } finally {
            leaked.close();
        }
    }

    @Test
    void disabledByDefault() throws Exception {
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .build());

        Connection c = ds.getConnection();
        try {
            Thread.sleep(200);
            assertThat(ds.snapshot().totalLeaks()).isZero();
        } finally {
            c.close();
        }
    }

    @Test
    void closeAndReBorrowResetsLeakDetection() throws Exception {
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .leakDetectionThreshold(Duration.ofMillis(150))
                .build());

        Connection a = ds.getConnection();
        try {
            waitFor(() -> ds.snapshot().totalLeaks() >= 1L, Duration.ofSeconds(2));
        } finally {
            a.close();
        }
        long afterFirst = ds.snapshot().totalLeaks();
        // Re-borrow the (likely same) entry — leak state must be cleared by markIdle in release().
        Connection b = ds.getConnection();
        try {
            // Hold it briefly under the threshold, no new leak should be reported.
            Thread.sleep(50);
            assertThat(ds.snapshot().totalLeaks()).isEqualTo(afterFirst);
        } finally {
            b.close();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, Duration timeout)
            throws InterruptedException, SQLException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met within " + timeout);
    }
}
