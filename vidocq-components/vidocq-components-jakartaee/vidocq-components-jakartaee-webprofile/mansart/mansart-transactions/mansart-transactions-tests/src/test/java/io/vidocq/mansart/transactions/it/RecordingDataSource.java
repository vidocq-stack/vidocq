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
package io.vidocq.mansart.transactions.it;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * A data source over an H2 in-memory database that remembers every connection it opened, so a test can count them
 * and see which ones were closed. With {@code xa}, it also answers {@code unwrap(XADataSource.class)}, the way a
 * pool with an XA driver does.
 */
final class RecordingDataSource implements DataSource {

    private final JdbcDataSource h2 = new JdbcDataSource();
    private final boolean xa;
    final List<Connection> plain = new CopyOnWriteArrayList<>();
    final List<XAConnection> xaConnections = new CopyOnWriteArrayList<>();
    final List<Connection> xaPhysical = new CopyOnWriteArrayList<>();

    RecordingDataSource(String database, boolean xa) {
        h2.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        this.xa = xa;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = h2.getConnection();
        plain.add(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = h2.getConnection(username, password);
        plain.add(connection);
        return connection;
    }

    /** The XA side: each XA connection is recorded with its physical connection. */
    private final class Xa implements XADataSource {
        @Override
        public XAConnection getXAConnection() throws SQLException {
            XAConnection connection = h2.getXAConnection();
            xaConnections.add(connection);
            xaPhysical.add(connection.getConnection().unwrap(Connection.class));
            return connection;
        }

        @Override
        public XAConnection getXAConnection(String user, String password) throws SQLException {
            return getXAConnection();
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { /* not used */ }
        @Override public void setLoginTimeout(int seconds) { /* not used */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    private final Xa xaSide = new Xa();

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (xa && type == XADataSource.class) return type.cast(xaSide);
        if (type.isInstance(this)) return type.cast(this);
        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return (xa && type == XADataSource.class) || type.isInstance(this);
    }

    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { /* not used */ }
    @Override public void setLoginTimeout(int seconds) { /* not used */ }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
