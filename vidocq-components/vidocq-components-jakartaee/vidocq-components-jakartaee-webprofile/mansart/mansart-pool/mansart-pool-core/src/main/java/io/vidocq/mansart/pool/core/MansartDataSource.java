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
package io.vidocq.mansart.pool.core;

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.PoolMetrics;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Pool front-end implementing {@link DataSource}. MP-A skeleton — borrow / restitution land in MP-B.
 *
 * <p>Constructed via {@link #of(PoolConfig)}. The instance is its own resource: call {@link #close()}
 * to drain the pool and reject further borrows.
 */
public final class MansartDataSource implements DataSource, AutoCloseable {

    private final PoolConfig     config;
    private final ConnectionPool pool;
    /** Built lazily from {@code config.xaDataSourceClassName()} — MANSART-007 phase 2. */
    private volatile javax.sql.XADataSource xaDataSource;

    private MansartDataSource(PoolConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.pool   = new ConnectionPool(config);
    }

    public static MansartDataSource of(PoolConfig config) {
        return new MansartDataSource(config);
    }

    /** The configuration this pool was built with. */
    public PoolConfig config() {
        return config;
    }

    /** Live snapshot of pool counters. */
    public PoolMetrics snapshot() {
        return pool.snapshot();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pool.acquire();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // Per-call credentials override is intentionally not supported — pool credentials are set
        // once via PoolConfig. Override at the DataSource level by building a second pool.
        throw new SQLFeatureNotSupportedException(
                "Per-call credentials are not supported; use PoolConfig.username/password");
    }

    @Override
    public void close() {
        pool.close();
    }

    /* ---- DataSource boilerplate ---- */

    @Override public PrintWriter getLogWriter()                  { return null; }
    @Override public void        setLogWriter(PrintWriter out)   { /* no-op */ }
    @Override public void        setLoginTimeout(int seconds)    { /* no-op — see PoolConfig.acquireTimeout */ }
    @Override public int         getLoginTimeout()               { return (int) config.acquireTimeout().toSeconds(); }
    @Override public Logger      getParentLogger()               { return Logger.getLogger("io.vidocq.mansart.pool"); }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return (T) this;
        if (iface == javax.sql.XADataSource.class && config.xaDataSourceClassName() != null) {
            return (T) xaDataSource();
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this)
                || (iface == javax.sql.XADataSource.class && config.xaDataSourceClassName() != null);
    }

    /**
     * The driver's {@link javax.sql.XADataSource} configured with this pool's URL/credentials
     * (MANSART-007 phase 2). Built once by reflection from {@code xaDataSourceClassName} —
     * config-driven, so the pool stays free of driver dependencies. Transactional XA
     * connections are opened on it OUTSIDE the pool, one per (transaction × datasource);
     * pooled connections are unaffected.
     */
    private javax.sql.XADataSource xaDataSource() throws SQLException {
        javax.sql.XADataSource existing = xaDataSource;
        if (existing != null) return existing;
        synchronized (this) {
            if (xaDataSource == null) {
                xaDataSource = buildXaDataSource();
            }
            return xaDataSource;
        }
    }

    private javax.sql.XADataSource buildXaDataSource() throws SQLException {
        String className = config.xaDataSourceClassName();
        try {
            Class<?> type = Class.forName(className, true, resolveDriverLoader());
            Object instance = type.getDeclaredConstructor().newInstance();
            setBeanProperty(type, instance, new String[]{"setURL", "setUrl"}, config.jdbcUrl());
            if (config.username() != null) {
                setBeanProperty(type, instance, new String[]{"setUser"}, config.username());
            }
            if (config.password() != null) {
                setBeanProperty(type, instance, new String[]{"setPassword"}, config.password());
            }
            return (javax.sql.XADataSource) instance;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new SQLException("Cannot build the XADataSource '" + className
                    + "' configured for this pool (xaDataSourceClassName)", e);
        }
    }

    private static ClassLoader resolveDriverLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : MansartDataSource.class.getClassLoader();
    }

    private static void setBeanProperty(Class<?> type, Object instance,
                                        String[] setterCandidates, String value)
            throws ReflectiveOperationException {
        for (String name : setterCandidates) {
            try {
                type.getMethod(name, String.class).invoke(instance, value);
                return;
            } catch (NoSuchMethodException tryNext) {
                // driver naming differs (setURL vs setUrl) — try the next candidate
            }
        }
        throw new NoSuchMethodException(type.getName() + " has none of "
                + String.join("/", setterCandidates) + "(String)");
    }
}
