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
package io.vidocq.mansart.data.cdi;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Adapts a bare {@link XADataSource} bean (a {@code @Named} datasource exposed only through the
 * XA interface) to the {@link DataSource} contract mansart-data consumes (MANSART-007).
 *
 * <p>Outside a transaction, {@link #getConnection()} opens an {@link XAConnection} and hands out
 * its logical connection — closing it closes the XA connection underneath (registered listener).
 * Inside a transaction, {@code JtaTransactionBridge} bypasses this path entirely: it detects the
 * wrapper via {@link #unwrap unwrap(XADataSource.class)} and enlists the driver's XAResource.
 */
final class XaBackedDataSource implements DataSource {

    private final XADataSource xaDataSource;

    XaBackedDataSource(XADataSource xaDataSource) {
        this.xaDataSource = Objects.requireNonNull(xaDataSource, "xaDataSource");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return logicalConnection(xaDataSource.getXAConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return logicalConnection(xaDataSource.getXAConnection(username, password));
    }

    private static Connection logicalConnection(XAConnection xaConnection) throws SQLException {
        xaConnection.addConnectionEventListener(new javax.sql.ConnectionEventListener() {
            @Override public void connectionClosed(javax.sql.ConnectionEvent event) {
                try { xaConnection.close(); } catch (SQLException ignored) { /* best effort */ }
            }
            @Override public void connectionErrorOccurred(javax.sql.ConnectionEvent event) {
                try { xaConnection.close(); } catch (SQLException ignored) { /* best effort */ }
            }
        });
        return xaConnection.getConnection();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface == XADataSource.class) return (T) xaDataSource;
        if (iface.isInstance(this)) return (T) this;
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface == XADataSource.class || iface.isInstance(this);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return xaDataSource.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { xaDataSource.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { xaDataSource.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return xaDataSource.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return xaDataSource.getParentLogger(); }
}
