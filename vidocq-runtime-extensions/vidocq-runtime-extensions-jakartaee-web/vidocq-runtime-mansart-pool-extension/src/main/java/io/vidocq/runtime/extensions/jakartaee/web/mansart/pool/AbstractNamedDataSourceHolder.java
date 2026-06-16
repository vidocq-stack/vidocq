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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Base class for the compile-time generated {@code @Named} {@link DataSource} holders. A generated
 * subclass carries the {@code @Named("X") @Singleton} qualifiers and passes its name to this
 * constructor; every JDBC call then forwards to the DataSource registered under that name in
 * {@link NamedDataSourceRegistry}.
 *
 * <p>Mirrors {@link MansartPoolHolder} (the {@code @Default} holder): a {@code @Singleton} subclass
 * avoids CDI client-proxy generation, so a lookup on {@code DataSource.class} with the name
 * qualifier resolves to the real holder instance and the cast is safe.
 */
public abstract class AbstractNamedDataSourceHolder implements DataSource {

    private final String name;

    protected AbstractNamedDataSourceHolder(String name) {
        this.name = name;
    }

    private DataSource delegate() {
        return NamedDataSourceRegistry.require(name);
    }

    /** The datasource name this holder is bound to. */
    public final String dataSourceName() {
        return name;
    }

    @Override public Connection  getConnection() throws SQLException                                 { return delegate().getConnection(); }
    @Override public Connection  getConnection(String username, String password) throws SQLException { return delegate().getConnection(username, password); }
    @Override public PrintWriter getLogWriter() throws SQLException                                  { return delegate().getLogWriter(); }
    @Override public void        setLogWriter(PrintWriter out) throws SQLException                   { delegate().setLogWriter(out); }
    @Override public void        setLoginTimeout(int seconds) throws SQLException                    { delegate().setLoginTimeout(seconds); }
    @Override public int         getLoginTimeout() throws SQLException                               { return delegate().getLoginTimeout(); }
    @Override public Logger      getParentLogger() throws SQLFeatureNotSupportedException           { return delegate().getParentLogger(); }
    @Override public <T> T       unwrap(Class<T> iface) throws SQLException                          { return delegate().unwrap(iface); }
    @Override public boolean     isWrapperFor(Class<?> iface) throws SQLException                    { return delegate().isWrapperFor(iface); }
}
