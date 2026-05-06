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
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
