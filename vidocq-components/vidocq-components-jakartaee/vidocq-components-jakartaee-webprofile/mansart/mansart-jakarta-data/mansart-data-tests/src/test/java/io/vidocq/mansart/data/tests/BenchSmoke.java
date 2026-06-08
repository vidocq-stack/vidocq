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
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.core.MansartData;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone perf smoke — disabled in normal test runs. Trigger with
 * {@code mvn -pl mansart-data-tests test -Dtest=BenchSmoke#all -DexcludedGroups=}.
 *
 * <p>Numbers from this run land in {@code mansart-jakarta-data/BENCH.md} per the workspace
 * convention (see root {@code CLAUDE.md}). Not a JMH replacement — coarse single-shot timing only.
 */
@Disabled("benchmark — uncomment locally to run; see BENCH.md for procedure and history.")
class BenchSmoke {

    @Test
    void findByIdSingleThread() throws Exception {
        var ds = newPool();
        seed(ds, 10_000);
        try {
            MansartData md = MansartData.builder().dataSource(ds).build();
            AuthorRepository repo = md.repository(AuthorRepository.class);

            int warmup = 5_000;
            int iters  = 50_000;

            for (int i = 0; i < warmup; i++) repo.findById(1L + (i % 10_000));

            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) repo.findById(1L + (i % 10_000));
            long elapsedNs = System.nanoTime() - t0;

            double meanUs = elapsedNs / 1_000.0 / iters;
            double opsPerSec = iters * 1_000_000_000.0 / elapsedNs;
            System.out.printf("[BENCH] findById single-thread: %d ops in %.2f ms => %.2f µs/op, %.0f ops/s%n",
                    iters, elapsedNs / 1_000_000.0, meanUs, opsPerSec);
        } finally {
            ds.dispose();
        }
    }

    @Test
    void findByIdVirtualThreads() throws Exception {
        var ds = newPool();
        seed(ds, 10_000);
        try {
            MansartData md = MansartData.builder().dataSource(ds).build();
            AuthorRepository repo = md.repository(AuthorRepository.class);

            int threads      = 1_000;
            int opsPerThread = 200;
            int total        = threads * opsPerThread;

            // Warmup
            for (int i = 0; i < 5_000; i++) repo.findById(1L + (i % 10_000));

            CountDownLatch latch = new CountDownLatch(threads);
            AtomicLong errors = new AtomicLong();
            long t0 = System.nanoTime();
            try (var vts = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < threads; t++) {
                    final int seed = t;
                    vts.submit(() -> {
                        try {
                            for (int n = 0; n < opsPerThread; n++) {
                                if (repo.findById(1L + ((seed * 31 + n) % 10_000)).isEmpty()) errors.incrementAndGet();
                            }
                        } catch (Throwable ex) { errors.incrementAndGet(); }
                        finally { latch.countDown(); }
                    });
                }
                latch.await(120, TimeUnit.SECONDS);
            }
            long elapsedNs = System.nanoTime() - t0;
            double meanUs = elapsedNs / 1_000.0 / total;
            double opsPerSec = total * 1_000_000_000.0 / elapsedNs;
            System.out.printf("[BENCH] findById %d VTs * %d ops = %d total in %.2f ms => %.2f µs/op, %.0f ops/s, errors=%d%n",
                    threads, opsPerThread, total, elapsedNs / 1_000_000.0, meanUs, opsPerSec, errors.get());
        } finally {
            ds.dispose();
        }
    }

    /* helpers */

    private static JdbcConnectionPool newPool() {
        JdbcConnectionPool pool = JdbcConnectionPool.create(
                "jdbc:h2:mem:bench-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(64);
        return pool;
    }

    private static void seed(JdbcConnectionPool ds, int rows) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE \"authors\" ("
                    + "  \"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "  \"name\" VARCHAR(200) NOT NULL"
                    + ")");
            try (var ps = c.prepareStatement("INSERT INTO \"authors\" (\"name\") VALUES (?)")) {
                for (int i = 0; i < rows; i++) {
                    ps.setString(1, "Author " + i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }
}
