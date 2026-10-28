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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A borrowed connection forwards every JDBC call to the driver's connection (H2 here), and only intercepts
 * {@code close()}, which hands the connection back to the pool.
 */
class PooledConnectionPassthroughTest {

    private static final String SELECT_ONE = "SELECT 1";

    private MansartDataSource ds;

    @BeforeEach
    void setUp() {
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxSize(2)
                .build());
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    private static int one(PreparedStatement ps) throws SQLException {
        try (ps; ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    @Test
    void forwardsStatementsAndCalls() throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (Statement s = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                 Statement h = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                         ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                assertThat(s.execute(SELECT_ONE)).isTrue();
                assertThat(h.execute(SELECT_ONE)).isTrue();
            }
            assertThat(one(c.prepareStatement(SELECT_ONE, Statement.RETURN_GENERATED_KEYS))).isEqualTo(1);
            assertThat(one(c.prepareStatement(SELECT_ONE, new int[]{1}))).isEqualTo(1);
            assertThat(one(c.prepareStatement(SELECT_ONE, new String[]{"X"}))).isEqualTo(1);
            assertThat(one(c.prepareStatement(SELECT_ONE, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY))).isEqualTo(1);
            assertThat(one(c.prepareStatement(SELECT_ONE, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT))).isEqualTo(1);
            assertThat(one(c.prepareCall(SELECT_ONE))).isEqualTo(1);
            assertThat(one(c.prepareCall(SELECT_ONE, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY))).isEqualTo(1);
            assertThat(one(c.prepareCall(SELECT_ONE, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT))).isEqualTo(1);
            assertThat(c.nativeSQL(SELECT_ONE)).isEqualTo(SELECT_ONE);
        }
    }

    @Test
    void forwardsTransactionsAndSavepoints() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (v INT)");
            c.setAutoCommit(false);
            assertThat(c.getAutoCommit()).isFalse();
            s.execute("INSERT INTO t VALUES (1)");
            Savepoint named = c.setSavepoint("named");
            s.execute("INSERT INTO t VALUES (2)");
            c.rollback(named);
            Savepoint unnamed = c.setSavepoint();
            c.releaseSavepoint(unnamed);
            c.commit();
            s.execute("INSERT INTO t VALUES (3)");
            c.rollback();
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            c.setAutoCommit(true);
        }
    }

    @Test
    void forwardsSettingsAndMetadata() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setReadOnly(false);
            assertThat(c.isReadOnly()).isFalse();
            c.setCatalog(c.getCatalog());
            c.setSchema("PUBLIC");
            assertThat(c.getSchema()).isEqualTo("PUBLIC");
            c.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
            assertThat(c.getHoldability()).isEqualTo(ResultSet.HOLD_CURSORS_OVER_COMMIT);
            c.clearWarnings();
            assertThat(java.util.Optional.ofNullable(c.getWarnings())).isEmpty();
            c.setTypeMap(new HashMap<>());
            assertThat(c.getTypeMap()).isEmpty();
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            assertThat(c.isValid(1)).isTrue();
            assertThat(c.getClientInfo()).isNotNull();
            assertThat(c.getClientInfo("ApplicationName")).isNull();
            assertThat(c.getNetworkTimeout()).isZero();
        }
    }

    @Test
    void forwardsLargeObjectsArraysAndUnwrapping() throws SQLException {
        try (Connection c = ds.getConnection()) {
            assertThat(c.createClob().length()).isZero();
            assertThat(c.createBlob().length()).isZero();
            assertThat(c.createNClob().length()).isZero();
            assertThat((Object[]) c.createArrayOf("INTEGER", new Object[]{1, 2}).getArray()).containsExactly(1, 2);

            assertThat(c.isWrapperFor(Connection.class)).isTrue();
            assertThat(c.unwrap(Connection.class)).isSameAs(c);
            assertThat(c.isWrapperFor(org.h2.jdbc.JdbcConnection.class)).isTrue();
            assertThat(c.unwrap(org.h2.jdbc.JdbcConnection.class)).isNotNull();
        }
    }

    @Test
    void closeReturnsTheConnectionOnce() throws SQLException {
        Connection c = ds.getConnection();
        assertThat(c.isClosed()).isFalse();
        c.close();
        c.close();

        assertThat(c.isClosed()).isTrue();
        assertThat(ds.snapshot().idle()).isEqualTo(1);
        assertThat(ds.snapshot().active()).isZero();
    }
}
