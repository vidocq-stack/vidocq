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
package io.vidocq.mansart.pool.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.core.MansartDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One resource-local transaction per operation, the way a JPA provider runs one (Vidocq/noether#45): borrow,
 * leave auto-commit, run one statement, commit, hand the connection back. The connection comes back out of
 * auto-commit with nothing pending, so this measures what the pool's release costs on top of the work, the reset
 * rollback in particular. Run it with {@code -prof gc} to see the allocations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TransactionCycleBench {

    @Param({"mansart", "hikari"})
    public String pool;

    private DataSource ds;
    private int next;

    @Setup
    public void setUp() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:bench-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        switch (pool) {
            case "mansart" -> ds = MansartDataSource.of(PoolConfig.builder()
                    .jdbcUrl(jdbcUrl).username("sa").maxSize(16).build());
            case "hikari"  -> {
                HikariConfig cfg = new HikariConfig();
                cfg.setJdbcUrl(jdbcUrl);
                cfg.setUsername("sa");
                cfg.setMaximumPoolSize(16);
                cfg.setConnectionTestQuery(null);
                ds = new HikariDataSource(cfg);
            }
            default -> throw new IllegalArgumentException(pool);
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE book (id INT PRIMARY KEY, title VARCHAR(64), pages INT)");
            s.execute("INSERT INTO book SELECT x, 'Title ' || x, x FROM SYSTEM_RANGE(1, 1000)");
        }
    }

    @TearDown
    public void tearDown() {
        if (ds instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    /** A read: find one row by its key inside a transaction. */
    @Benchmark
    public int findByIdCommit() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            int pages;
            try (PreparedStatement ps = c.prepareStatement("SELECT title, pages FROM book WHERE id = ?")) {
                ps.setInt(1, nextId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    pages = rs.getInt(2);
                }
            }
            c.commit();
            return pages;
        }
    }

    /** A write: update one row by its key inside a transaction. */
    @Benchmark
    public int updateCommit() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            int updated;
            try (PreparedStatement ps = c.prepareStatement("UPDATE book SET pages = pages + 1 WHERE id = ?")) {
                ps.setInt(1, nextId());
                updated = ps.executeUpdate();
            }
            c.commit();
            return updated;
        }
    }

    private int nextId() {
        next = next % 1000 + 1;
        return next;
    }
}
