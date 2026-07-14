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
package io.vidocq.mansart.data.tests;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;

/**
 * Datasources for the JTA transaction-bridge tests (MANSART-007), one per enlistment path:
 * <ul>
 *   <li>{@code @Default} — a {@link PlainDataSource} that deliberately hides the XA capability
 *       of the underlying H2 datasource, forcing the bridge onto the LRCO
 *       ({@code ConnectionXAResource}) fallback;</li>
 *   <li>{@code @Named("bridgeds")} — a bare H2 {@link JdbcDataSource}, which IS an
 *       {@code XADataSource}, exercising the real-XA enlistment path.</li>
 * </ul>
 * A transaction touching both therefore exercises the mixed LRCO ordering
 * (single-phase resource committed first).
 */
@Singleton
public class BridgeDataSources {

    public static final String DEFAULT_URL = "jdbc:h2:mem:mansart-bridge-default;DB_CLOSE_DELAY=-1";
    public static final String AUDIT_URL   = "jdbc:h2:mem:mansart-bridge-audit;DB_CLOSE_DELAY=-1";
    public static final String XAONLY_URL  = "jdbc:h2:mem:mansart-bridge-xaonly;DB_CLOSE_DELAY=-1";

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD, FIELD, PARAMETER})
    public @interface BridgeStore {
    }

    @Produces
    @Singleton
    public DataSource defaultDataSource() {
        return new PlainDataSource(h2(DEFAULT_URL));
    }

    @Produces
    @Singleton
    @BridgeStore
    @Named("bridgeds")
    public DataSource bridgeDataSource() {
        return h2(AUDIT_URL);
    }

    /**
     * Exposed ONLY as {@link javax.sql.XADataSource} — exercises the
     * {@code DataStoreResolver} XA-bean path ({@code XaBackedDataSource} adapter).
     */
    @Produces
    @Singleton
    @BridgeStore
    @Named("xaonly")
    public javax.sql.XADataSource xaOnlyDataSource() {
        return h2(XAONLY_URL);
    }

    static JdbcDataSource h2(String url) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        return ds;
    }

    /** Hides the delegate's XA capability: {@code isWrapperFor(XADataSource.class)} is false. */
    static final class PlainDataSource implements DataSource {
        private final DataSource delegate;

        PlainDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override public Connection  getConnection() throws SQLException { return delegate.getConnection(); }
        @Override public Connection  getConnection(String u, String p) throws SQLException { return delegate.getConnection(u, p); }
        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void        setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void        setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int         getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger      getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
        @Override public <T> T       unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean     isWrapperFor(Class<?> iface) { return false; }
    }
}
