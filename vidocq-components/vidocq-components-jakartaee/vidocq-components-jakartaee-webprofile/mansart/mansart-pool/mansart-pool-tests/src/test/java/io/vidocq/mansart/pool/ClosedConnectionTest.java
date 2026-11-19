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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A connection handed back to the pool is dead for the borrower that closed it: the handle, and the statements
 * it created, no longer reach the physical connection, which another borrower may hold by then. The pool has a
 * single connection here, so the second borrow gets the first one's.
 */
class ClosedConnectionTest {

    private MansartDataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxSize(1)
                .acquireTimeout(Duration.ofMillis(200))
                .build());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT PRIMARY KEY)");
        }
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void aClosedHandleCannotRollBackTheNextBorrowersTransaction() throws SQLException {
        Connection stale = ds.getConnection();
        stale.close();

        try (Connection current = ds.getConnection()) {
            current.setAutoCommit(false);
            try (Statement s = current.createStatement()) {
                s.executeUpdate("INSERT INTO t VALUES (1)");
            }

            assertThatThrownBy(stale::rollback).isInstanceOf(SQLException.class);
            current.commit();
        }

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aStatementOfAClosedHandleCannotWriteOnTheNextBorrowersConnection() throws SQLException {
        Connection stale = ds.getConnection();
        Statement staleStatement = stale.createStatement();
        stale.close();

        try (Connection current = ds.getConnection()) {
            current.setAutoCommit(false);
            assertThatThrownBy(() -> staleStatement.executeUpdate("INSERT INTO t VALUES (2)"))
                    .isInstanceOf(SQLException.class);
            current.commit();
        }

        assertThat(count()).isZero();
    }

    /** Two threads closing one handle give the pool its connection back once: {@code maxSize} still holds. */
    @Test
    void closingAHandleFromTwoThreadsReleasesItOnce() throws Exception {
        for (int i = 0; i < 2_000; i++) {
            Connection shared = ds.getConnection();
            var barrier = new CyclicBarrier(2);
            Runnable close = () -> {
                try {
                    barrier.await();
                    shared.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            Thread a = Thread.ofPlatform().start(close);
            Thread b = Thread.ofPlatform().start(close);
            a.join();
            b.join();

            assertThat(ds.snapshot().idle()).as("iteration %d: idle connections of a pool of one", i).isEqualTo(1);
        }
    }

    @Test
    void aClosedHandleRefusesEveryCallButCloseIsClosedAndIsValid() throws SQLException {
        Connection stale = ds.getConnection();
        stale.close();

        assertThat(stale.isClosed()).isTrue();
        assertThat(stale.isValid(1)).isFalse();
        stale.close();
        assertThatThrownBy(stale::createStatement).isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("08003");
        assertThatThrownBy(() -> stale.setAutoCommit(false)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> stale.setReadOnly(true)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> stale.unwrap(org.h2.jdbc.JdbcConnection.class)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> stale.setClientInfo("ApplicationName", "x"))
                .isInstanceOf(java.sql.SQLClientInfoException.class);
    }

    private int count() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
