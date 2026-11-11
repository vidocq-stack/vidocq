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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connection handed back out of auto-commit is rolled back only when work may be pending (Vidocq/noether#45,
 * finding F17). A transaction the client already committed or rolled back costs no second rollback; work the
 * client left behind, or work the pool cannot see, is still rolled back before the next borrower gets the
 * connection.
 */
class ReleaseRollbackTest {

    private static final String INSERT = "INSERT INTO t VALUES (?, ?)";

    private CountingDriver driver;
    private MansartDataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        driver = new CountingDriver();
        DriverManager.registerDriver(driver);
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl(CountingDriver.PREFIX + "h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxSize(2)
                .build());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
        }
        driver.rollbacks.set(0);
    }

    @AfterEach
    void tearDown() throws SQLException {
        ds.close();
        DriverManager.deregisterDriver(driver);
    }

    @Test
    void aCommittedTransactionIsNotRolledBackOnRelease() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            insert(ps, 1);
            c.commit();
        }

        assertThat(driver.rollbacks.get()).isZero();
        assertThat(rows()).isEqualTo(1);
        try (Connection c = ds.getConnection()) {
            assertThat(c.getAutoCommit()).isTrue();
        }
    }

    @Test
    void aRolledBackTransactionIsNotRolledBackTwice() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            insert(ps, 1);
            c.rollback();
        }

        assertThat(driver.rollbacks.get()).isEqualTo(1);
        assertThat(rows()).isZero();
    }

    @Test
    void aConnectionReturnedMidTransactionIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            insert(ps, 1);
        }

        assertThat(rows()).isZero();
        try (Connection c = ds.getConnection()) {
            assertThat(c.getAutoCommit()).isTrue();
        }
    }

    @Test
    void aStatementPreparedBeforeTheCommitAndRunAfterItIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            insert(ps, 1);
            c.commit();
            insert(ps, 2);
        }

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void rollingBackToASavepointLeavesTheEarlierWorkPending() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            insert(ps, 1);
            Savepoint savepoint = c.setSavepoint();
            insert(ps, 2);
            c.rollback(savepoint);
        }

        assertThat(rows()).isZero();
    }

    @Test
    void aBatchRunAfterTheCommitIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            c.commit();
            ps.setInt(1, 1);
            ps.setInt(2, 1);
            ps.addBatch();
            ps.executeBatch();
        }

        assertThat(rows()).isZero();
    }

    @Test
    void aCallableStatementRunAfterTheCommitIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.commit();
            try (var call = c.prepareCall("INSERT INTO t VALUES (1, 1)")) {
                call.execute();
            }
        }

        assertThat(rows()).isZero();
    }

    @Test
    void workOnTheUnwrappedDriverConnectionIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.commit();
            try (Statement s = c.unwrap(org.h2.jdbc.JdbcConnection.class).createStatement()) {
                s.executeUpdate("INSERT INTO t VALUES (1, 1)");
            }
        }

        assertThat(rows()).isZero();
    }

    @Test
    void workOnAnUnwrappedDriverStatementIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            c.commit();
            s.unwrap(org.h2.jdbc.JdbcStatement.class).executeUpdate("INSERT INTO t VALUES (1, 1)");
        }

        assertThat(rows()).isZero();
    }

    @Test
    void workOnTheMetadataConnectionIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.commit();
            try (Statement s = c.getMetaData().getConnection().createStatement()) {
                s.executeUpdate("INSERT INTO t VALUES (1, 1)");
            }
        }

        assertThat(rows()).isZero();
    }

    @Test
    void anUpdatableResultSetUpdatedAfterTheCommitIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO t VALUES (1, 1)");
        }
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE,
                     ResultSet.HOLD_CURSORS_OVER_COMMIT)) {
            c.setAutoCommit(false);
            try (ResultSet rs = s.executeQuery("SELECT id, v FROM t")) {
                c.commit();
                assertThat(rs.next()).isTrue();
                rs.updateInt(2, 42);
                rs.updateRow();
            }
        }

        assertThat(value(1)).isEqualTo(1);
    }

    @Test
    void aLargeObjectCreatedAfterTheCommitIsRolledBack() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.commit();
            c.createBlob().free();
        }

        assertThat(driver.rollbacks.get()).isEqualTo(1);
    }

    @Test
    void aStatementHandsOutThePooledConnection() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             PreparedStatement ps = c.prepareStatement(INSERT)) {
            assertThat(s.getConnection()).isSameAs(c);
            assertThat(ps.getConnection()).isSameAs(c);
        }
    }

    /* ---- helpers ---- */

    private static void insert(PreparedStatement ps, int id) throws SQLException {
        ps.setInt(1, id);
        ps.setInt(2, id);
        ps.executeUpdate();
    }

    private int rows() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private int value(int id) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT v FROM t WHERE id = " + id)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    /**
     * H2 behind a {@code jdbc:counting:} prefix, counting the {@code rollback()} calls its connections receive. A
     * dynamic proxy is fine here: it is test code, the shipped pool never sees it as anything but a driver.
     */
    static final class CountingDriver implements Driver {

        static final String PREFIX = "jdbc:counting:";

        final AtomicInteger rollbacks = new AtomicInteger();
        private final Driver h2 = new org.h2.Driver();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            Connection real = h2.connect("jdbc:" + url.substring(PREFIX.length()), info);
            return (Connection) Proxy.newProxyInstance(ReleaseRollbackTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (_, method, args) -> {
                        if ("rollback".equals(method.getName()) && method.getParameterCount() == 0) {
                            rollbacks.incrementAndGet();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override public boolean acceptsURL(String url)                                 { return url.startsWith(PREFIX); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion()                                          { return 1; }
        @Override public int getMinorVersion()                                          { return 0; }
        @Override public boolean jdbcCompliant()                                        { return false; }
        @Override public Logger getParentLogger()                                       { return Logger.getGlobal(); }
    }
}
