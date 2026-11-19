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
import org.junit.jupiter.api.Test;

import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MANSART-065 — the XA connections of a pool configured with {@code xaDataSourceClassName} are the pool's own:
 * they count against {@code maxSize}, show in the metrics and are used again, where each transaction opened a
 * physical connection of its own, outside the pool.
 */
class XaConnectionsThroughPoolTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private static PoolConfig.Builder xaPool() {
        return PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:mansart-pool-xa-through-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .minIdle(0)
                .acquireTimeout(Duration.ofMillis(200))
                .xaDataSourceClassName("org.h2.jdbcx.JdbcDataSource");
    }

    private static int sessionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT SESSION_ID()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void run(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record TestXid(int branch) implements Xid {
        @Override public int getFormatId() { return 0x4d414e53; }
        @Override public byte[] getGlobalTransactionId() { return new byte[] {1, 2, 3, (byte) branch}; }
        @Override public byte[] getBranchQualifier() { return new byte[] {(byte) branch}; }
    }

    @Test
    void anXaConnectionCountsAgainstMaxSize() throws Exception {
        try (MansartDataSource pool = MansartDataSource.of(xaPool().maxSize(1).build())) {
            XADataSource xa = pool.unwrap(XADataSource.class);
            XAConnection held = xa.getXAConnection();
            try {
                assertThat(pool.snapshot().active()).isEqualTo(1);
                assertThatThrownBy(xa::getXAConnection)
                        .isInstanceOfSatisfying(PoolException.class,
                                e -> assertThat(e.reason()).isEqualTo(PoolException.Reason.ACQUIRE_TIMEOUT));
                assertThatThrownBy(pool::getConnection)
                        .isInstanceOfSatisfying(PoolException.class,
                                e -> assertThat(e.reason()).isEqualTo(PoolException.Reason.ACQUIRE_TIMEOUT));
            } finally {
                held.close();
            }
            assertThat(pool.snapshot().active()).isZero();
            assertThat(pool.snapshot().idle()).isEqualTo(1);
        }
    }

    @Test
    void aClosedXaConnectionIsTheNextBorrowersConnection() throws Exception {
        try (MansartDataSource pool = MansartDataSource.of(xaPool().maxSize(4).build())) {
            XADataSource xa = pool.unwrap(XADataSource.class);

            XAConnection first = xa.getXAConnection();
            int session = sessionId(first.getConnection());
            first.close();

            XAConnection second = xa.getXAConnection();
            try {
                assertThat(sessionId(second.getConnection())).isEqualTo(session);
            } finally {
                second.close();
            }
            try (Connection plain = pool.getConnection()) {
                assertThat(sessionId(plain)).isEqualTo(session);
            }
        }
    }

    @Test
    void aTwoPhaseBranchCommitsThroughAPooledXaConnection() throws Exception {
        try (MansartDataSource pool = MansartDataSource.of(xaPool().maxSize(2).build())) {
            try (Connection setup = pool.getConnection()) {
                run(setup, "CREATE TABLE note(id INT PRIMARY KEY)");
            }
            XAConnection xaConnection = pool.unwrap(XADataSource.class).getXAConnection();
            Xid xid = new TestXid(1);
            XAResource resource = xaConnection.getXAResource();
            resource.start(xid, XAResource.TMNOFLAGS);
            run(xaConnection.getConnection(), "INSERT INTO note VALUES (1)");
            resource.end(xid, XAResource.TMSUCCESS);
            assertThat(resource.prepare(xid)).isEqualTo(XAResource.XA_OK);
            resource.commit(xid, false);
            xaConnection.close();

            assertThat(pool.snapshot().idle()).as("the connection of a finished branch is kept").isEqualTo(1);
            try (Connection reader = pool.getConnection()) {
                assertThat(reader.getAutoCommit()).isTrue();
                assertThat(count(reader, "note")).isEqualTo(1);
            }
        }
    }

    @Test
    void aConnectionClosedInsideItsBranchIsNotUsedAgain() throws Exception {
        try (MansartDataSource pool = MansartDataSource.of(xaPool().maxSize(2).build())) {
            try (Connection setup = pool.getConnection()) {
                run(setup, "CREATE TABLE note(id INT PRIMARY KEY)");
            }
            XAConnection xaConnection = pool.unwrap(XADataSource.class).getXAConnection();
            Xid xid = new TestXid(2);
            xaConnection.getXAResource().start(xid, XAResource.TMNOFLAGS);
            Connection inBranch = xaConnection.getConnection();
            int session = sessionId(inBranch);
            run(inBranch, "INSERT INTO note VALUES (2)");
            xaConnection.close();

            try (Connection next = pool.getConnection()) {
                assertThat(sessionId(next)).as("a connection left inside a branch is closed").isNotEqualTo(session);
                assertThat(count(next, "note")).as("its work is rolled back").isZero();
            }
        }
    }

    @Test
    void aClosedXaConnectionNoLongerReachesThePhysicalConnection() throws Exception {
        try (MansartDataSource pool = MansartDataSource.of(xaPool().maxSize(1).build())) {
            XAConnection xaConnection = pool.unwrap(XADataSource.class).getXAConnection();
            Connection handle = xaConnection.getConnection();
            xaConnection.close();
            xaConnection.close();

            assertThat(pool.snapshot().idle()).as("closing twice gives the connection back once").isEqualTo(1);
            assertThat(handle.isClosed()).isTrue();
            assertThatThrownBy(handle::createStatement)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("08003"));
            assertThatThrownBy(xaConnection::getXAResource)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("08003"));
            assertThatThrownBy(xaConnection::getConnection)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("08003"));
        }
    }

    @Test
    void closingTheHandleKeepsTheXaConnectionAndTellsItsListeners() throws Exception {
        try (MansartDataSource pool = MansartDataSource.of(xaPool().maxSize(1).build())) {
            XAConnection xaConnection = pool.unwrap(XADataSource.class).getXAConnection();
            AtomicInteger closedEvents = new AtomicInteger();
            ConnectionEventListener listener = new ConnectionEventListener() {
                @Override public void connectionClosed(ConnectionEvent event) { closedEvents.incrementAndGet(); }
                @Override public void connectionErrorOccurred(ConnectionEvent event) { /* not under test */ }
            };
            xaConnection.addConnectionEventListener(listener);
            try {
                Connection first = xaConnection.getConnection();
                first.close();
                assertThat(closedEvents).hasValue(1);
                assertThat(pool.snapshot().active()).as("the XA connection is still out").isEqualTo(1);

                Connection second = xaConnection.getConnection();
                Connection third = xaConnection.getConnection();
                assertThat(second.isClosed()).as("a new handle closes the one before").isTrue();
                assertThat(sessionId(third)).isPositive();

                xaConnection.removeConnectionEventListener(listener);
                third.close();
                assertThat(closedEvents).hasValue(1);
            } finally {
                xaConnection.close();
            }
        }
    }

    @Test
    void perCallCredentialsAreRefusedOnTheXaSideToo() throws Exception {
        try (MansartDataSource pool = MansartDataSource.of(xaPool().build())) {
            XADataSource xa = pool.unwrap(XADataSource.class);
            assertThatThrownBy(() -> xa.getXAConnection("sa", ""))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }
    }

    @Test
    void driverPropertiesReachTheXaDataSourceThroughItsSetters() throws Exception {
        PoolConfig config = xaPool().driverProperty("description", "billing").build();
        try (MansartDataSource pool = MansartDataSource.of(config);
             Connection connection = pool.getConnection()) {
            assertThat(sessionId(connection)).isPositive();
        }
    }

    @Test
    void aDriverPropertyTheXaDataSourceCannotTakeFailsTheBorrow() {
        PoolConfig config = xaPool().driverProperty("noSuchProperty", "x").build();
        try (MansartDataSource pool = MansartDataSource.of(config)) {
            assertThatThrownBy(pool::getConnection)
                    .isInstanceOf(SQLException.class)
                    .hasStackTraceContaining("noSuchProperty");
        }
    }

    @Test
    void aDriverPropertyWithoutSetterGoesThroughSetProperty() throws Exception {
        PoolConfig config = xaPool()
                .xaDataSourceClassName(ScriptedXaDataSource.class.getName())
                .driverProperty("applicationName", "ledger")
                .build();
        try (MansartDataSource pool = MansartDataSource.of(config);
             Connection connection = pool.getConnection()) {
            assertThat(sessionId(connection)).isPositive();
            assertThat(ScriptedXaDataSource.PROPERTIES).containsEntry("applicationName", "ledger");
        }
    }

    @Test
    void anXaConnectionThatGivesNoLogicalConnectionIsClosed() {
        PoolConfig config = xaPool()
                .xaDataSourceClassName(ScriptedXaDataSource.class.getName())
                .driverProperty("handle", "broken")
                .build();
        int closedBefore = ScriptedXaDataSource.BROKEN_CLOSED.get();
        try (MansartDataSource pool = MansartDataSource.of(config)) {
            assertThatThrownBy(pool::getConnection)
                    .isInstanceOfSatisfying(PoolException.class,
                            e -> assertThat(e.reason()).isEqualTo(PoolException.Reason.CONNECT_FAILED));
            assertThat(ScriptedXaDataSource.BROKEN_CLOSED.get()).isEqualTo(closedBefore + 1);
            assertThat(pool.snapshot().active()).isZero();
        }
    }

    @Test
    void anUnknownXaClassFailsTheBorrow() {
        PoolConfig config = xaPool().xaDataSourceClassName("com.example.DoesNotExist").build();
        try (MansartDataSource pool = MansartDataSource.of(config)) {
            assertThatThrownBy(pool::getConnection)
                    .isInstanceOf(SQLException.class)
                    .hasStackTraceContaining("com.example.DoesNotExist");
        }
    }
}
