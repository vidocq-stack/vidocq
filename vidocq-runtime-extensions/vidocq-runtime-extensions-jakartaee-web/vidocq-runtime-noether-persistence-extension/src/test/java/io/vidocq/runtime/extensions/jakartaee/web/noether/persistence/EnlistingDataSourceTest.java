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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnlistingDataSourceTest {

    private final MansartTransactionManager manager = new MansartTransactionManager();
    private RecordingPool pool;
    private EnlistingDataSource enlisting;

    @BeforeEach
    void createTable() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:enlisting-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        pool = new RecordingPool(h2);
        enlisting = new EnlistingDataSource(pool, manager);
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table item(id int primary key)");
        }
    }

    @AfterEach
    void endAnyTransaction() throws Exception {
        if (manager.getTransaction() != null) {
            manager.rollback();
        }
    }

    @Test
    void outsideATransactionTheConnectionIsThePoolsOwnInAutoCommit() throws SQLException {
        try (Connection connection = enlisting.getConnection()) {
            assertTrue(connection.getAutoCommit());
            insert(connection, 1);
        }

        assertEquals(1, committedCount());
        assertTrue(pool.allReturned());
    }

    @Test
    void withinATransactionEveryCallSharesOneConnectionThatTheManagerCommits() throws Exception {
        manager.begin();
        try (Connection first = enlisting.getConnection()) {
            insert(first, 1);
        }
        try (Connection second = enlisting.getConnection()) {
            insert(second, 2);
            assertEquals(2, count(second), "the second call sees the first one's work: the same connection");
        }
        assertEquals(1, pool.handedOut(), "one physical connection for the whole transaction");
        assertEquals(0, committedCount(), "nothing is visible before the transaction commits");

        manager.commit();

        assertEquals(2, committedCount());
        assertTrue(pool.allReturned(), "the physical connection goes back to the pool once the transaction ends");
    }

    @Test
    void aRollbackOfTheManagerUndoesTheWorkAndReturnsTheConnection() throws Exception {
        manager.begin();
        try (Connection connection = enlisting.getConnection()) {
            insert(connection, 1);
        }

        manager.rollback();

        assertEquals(0, committedCount());
        assertTrue(pool.allReturned());
    }

    @Test
    void theConnectionOfATransactionRefusesALocalCompletion() throws Exception {
        manager.begin();
        try (Connection connection = enlisting.getConnection()) {
            assertFalse(connection.getAutoCommit());
            assertThrows(SQLException.class, connection::commit);
            assertThrows(SQLException.class, connection::rollback);
            assertThrows(SQLException.class, () -> connection.setAutoCommit(true));
        }
    }

    @Test
    void aClosedHandleRefusesWorkButLeavesTheTransactionsConnectionOpen() throws Exception {
        manager.begin();
        Connection handle = enlisting.getConnection();
        handle.close();

        assertTrue(handle.isClosed());
        assertThrows(SQLException.class, handle::createStatement);
        try (Connection next = enlisting.getConnection()) {
            insert(next, 1);
        }
        manager.commit();
        assertEquals(1, committedCount());
    }

    @Test
    void twoTransactionsUseTwoConnections() throws Exception {
        manager.begin();
        try (Connection connection = enlisting.getConnection()) {
            insert(connection, 1);
        }
        manager.commit();
        manager.begin();
        try (Connection connection = enlisting.getConnection()) {
            insert(connection, 2);
        }
        manager.commit();

        assertEquals(2, pool.handedOut());
        assertEquals(2, committedCount());
        assertTrue(pool.allReturned());
    }

    private static void insert(Connection connection, int id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into item(id) values (" + id + ")");
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from item")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** What another connection, outside any transaction, sees. */
    private int committedCount() throws SQLException {
        try (Connection connection = pool.delegate.getConnection()) {
            return count(connection);
        }
    }

    /** A pool that remembers the connections it handed out, to tell whether they all came back. */
    private static final class RecordingPool implements DataSource {
        private final JdbcDataSource delegate;
        private final List<Connection> handedOut = new CopyOnWriteArrayList<>();

        RecordingPool(JdbcDataSource delegate) {
            this.delegate = delegate;
        }

        int handedOut() {
            return handedOut.size();
        }

        boolean allReturned() throws SQLException {
            for (Connection connection : handedOut) {
                if (!connection.isClosed()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            handedOut.add(connection);
            return connection;
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            Connection connection = delegate.getConnection(user, password);
            handedOut.add(connection);
            return connection;
        }

        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return delegate.getParentLogger(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
    }
}
