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

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANSART-065 against a real PostgreSQL: the pool opens its connections on {@code PGXADataSource}, the
 * transaction manager drives two of them through a real two-phase commit, and the same physical connections
 * serve the following transactions.
 *
 * <p>Excluded from the default run via the {@code pg-it} JUnit tag ({@code -Ppg-it}, requires Docker).
 */
@Tag("pg-it")
@Testcontainers
class PostgresqlXaPoolIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("mansart")
            .withUsername("mansart")
            .withPassword("mansart")
            .withCommand("postgres", "-c", "max_prepared_transactions=10");

    private static MansartDataSource pool;

    @BeforeAll
    static void openPool() throws SQLException {
        pool = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .minIdle(0)
                .maxSize(2)
                .acquireTimeout(Duration.ofSeconds(5))
                .driverProperty("applicationName", "mansart-xa-pool")
                .xaDataSourceClassName("org.postgresql.xa.PGXADataSource")
                .build());
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE xa_note(id INT PRIMARY KEY)");
        }
    }

    @AfterAll
    static void closePool() {
        pool.close();
    }

    private static int backend(Connection connection) throws SQLException {
        return queryInt(connection, "SELECT pg_backend_pid()");
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void insert(Connection connection, int id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO xa_note VALUES (" + id + ")");
        }
    }

    /** One transaction on two XA connections of the pool: a real prepare on each, then commit or rollback. */
    private static Set<Integer> twoBranches(MansartTransactionManager tm, int firstId, boolean commit) throws Exception {
        XADataSource xa = pool.unwrap(XADataSource.class);
        Set<Integer> backends = new HashSet<>();
        tm.begin();
        XAConnection first = xa.getXAConnection();
        XAConnection second = xa.getXAConnection();
        try {
            tm.getTransaction().enlistResource(first.getXAResource());
            tm.getTransaction().enlistResource(second.getXAResource());
            assertThat(pool.snapshot().active()).isEqualTo(2);
            backends.add(backend(first.getConnection()));
            backends.add(backend(second.getConnection()));
            insert(first.getConnection(), firstId);
            insert(second.getConnection(), firstId + 1);
            if (commit) {
                tm.commit();
            } else {
                tm.rollback();
            }
        } finally {
            first.close();
            second.close();
        }
        return backends;
    }

    @Test
    void twoPhaseCommitsRunOnTheSamePooledXaConnections() throws Exception {
        MansartTransactionManager tm = new MansartTransactionManager();

        Set<Integer> firstRun = twoBranches(tm, 1, true);
        assertThat(firstRun).hasSize(2);
        assertThat(pool.snapshot().active()).isZero();
        assertThat(pool.snapshot().idle()).as("both connections are kept after the commit").isEqualTo(2);

        Set<Integer> rolledBack = twoBranches(tm, 10, false);
        Set<Integer> thirdRun = twoBranches(tm, 20, true);
        assertThat(rolledBack).as("no connection was opened for the second transaction").isEqualTo(firstRun);
        assertThat(thirdRun).as("nor for the third").isEqualTo(firstRun);

        try (Connection reader = pool.getConnection()) {
            assertThat(reader.getAutoCommit()).isTrue();
            assertThat(firstRun).contains(backend(reader));
            assertThat(queryInt(reader, "SELECT COUNT(*) FROM xa_note")).isEqualTo(4);
            assertThat(queryInt(reader, "SELECT COUNT(*) FROM pg_prepared_xacts")).isZero();
            assertThat(queryInt(reader,
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name = 'mansart-xa-pool'"))
                    .as("the driver property reached PGXADataSource; two connections in all")
                    .isEqualTo(2);
        }
    }
}
