/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.it.mansart;

import jakarta.inject.Singleton;
import jakarta.inject.Named;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

@Singleton
@Named("vidocq-it")
public class TestDataSource implements DataSource, AutoCloseable {
    private static final JdbcDataSource DELEGATE = createDelegate();
    private static volatile int acquisitions;
    private static volatile boolean closed;

    private static JdbcDataSource createDelegate() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:vidocq-mansart-it;DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        return source;
    }

    static int acquisitions() {
        return acquisitions;
    }

    static boolean closed() {
        return closed;
    }

    @Override
    public Connection getConnection() throws SQLException {
        acquisitions++;
        return DELEGATE.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        acquisitions++;
        return DELEGATE.getConnection(username, password);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return DELEGATE.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { DELEGATE.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { DELEGATE.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return DELEGATE.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return DELEGATE.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        return DELEGATE.unwrap(iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || DELEGATE.isWrapperFor(iface);
    }
    @Override public void close() { closed = true; }
}
