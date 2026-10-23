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

package io.vidocq.bodoni.core.testing;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A {@link DataSource} double that opens {@link RecordingConnection}s through
 * {@link DriverManager} (user {@code sa}, empty password) and counts them. Every other method
 * throws {@link SQLFeatureNotSupportedException}: Bodoni must call {@code getConnection()} only.
 */
public final class CountingDataSource implements DataSource {

    private static final String USER = "sa";

    private final String url;
    private final List<RecordingConnection> connections = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<RecordingConnection> onOpen = c -> { };

    public CountingDataSource(String url) {
        this.url = url;
    }

    /** Applies {@code configure} to every connection opened from now on, before it is handed out. */
    public CountingDataSource onOpen(Consumer<RecordingConnection> configure) {
        this.onOpen = configure;
        return this;
    }

    /** The number of connections opened. */
    public int opened() {
        return connections.size();
    }

    /** The connections opened, in order. */
    public List<RecordingConnection> connections() {
        return List.copyOf(connections);
    }

    @Override
    public Connection getConnection() throws SQLException {
        RecordingConnection connection = new RecordingConnection(DriverManager.getConnection(url, USER, ""));
        onOpen.accept(connection);
        connections.add(connection);
        return connection.proxy();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw unsupported();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        throw unsupported();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw unsupported();
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        throw unsupported();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw unsupported();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw unsupported();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        throw unsupported();
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("Bodoni must call getConnection() only");
    }
}
