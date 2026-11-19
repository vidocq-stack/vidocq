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

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.ConnectionEventListener;
import javax.sql.StatementEventListener;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * An {@link XADataSource} named in {@code xaDataSourceClassName} by {@code XaConnectionsThroughPoolTest}: H2's,
 * with the generic {@code setProperty(String, String)} of PostgreSQL's data source instead of a bean setter per
 * property, and a {@code handle=broken} property that makes its XA connections refuse their logical connection.
 */
public final class ScriptedXaDataSource implements XADataSource {

    /** Every property a pool handed over, by name. */
    static final Map<String, String> PROPERTIES = new ConcurrentHashMap<>();
    /** XA connections closed after they refused their logical connection. */
    static final AtomicInteger BROKEN_CLOSED = new AtomicInteger();

    private final JdbcDataSource h2 = new JdbcDataSource();
    private volatile boolean broken;

    public void setURL(String url) {
        h2.setURL(url);
    }

    public void setUser(String user) {
        h2.setUser(user);
    }

    public void setProperty(String name, String value) {
        PROPERTIES.put(name, value);
        if ("handle".equals(name)) {
            broken = "broken".equals(value);
        }
    }

    @Override
    public XAConnection getXAConnection() throws SQLException {
        XAConnection real = h2.getXAConnection();
        return broken ? new Broken(real) : real;
    }

    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        return h2.getXAConnection(user, password);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return h2.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { h2.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { h2.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return h2.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return h2.getParentLogger(); }

    private record Broken(XAConnection real) implements XAConnection {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("no logical connection today");
        }

        @Override
        public void close() throws SQLException {
            BROKEN_CLOSED.incrementAndGet();
            real.close();
        }

        @Override public XAResource getXAResource() throws SQLException { return real.getXAResource(); }
        @Override public void addConnectionEventListener(ConnectionEventListener l) { real.addConnectionEventListener(l); }
        @Override public void removeConnectionEventListener(ConnectionEventListener l) { real.removeConnectionEventListener(l); }
        @Override public void addStatementEventListener(StatementEventListener l) { real.addStatementEventListener(l); }
        @Override public void removeStatementEventListener(StatementEventListener l) { real.removeStatementEventListener(l); }
    }
}
