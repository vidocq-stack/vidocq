package io.vidocq.mpserver.ext.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * CDI bean exposing the {@link MansartDataSource} built by {@link MansartPoolExtension} as a
 * first-class {@link DataSource} bean for any {@code @Inject DataSource} site.
 *
 * <p><b>Why does this class implement DataSource directly</b> instead of relying on
 * {@code @Produces DataSource}?
 * <ul>
 *   <li>Vauban CDI Lite resolves a {@code @Produces} method by handing back the producer bean's
 *       client proxy when the requested type matches the producer's return type — and the proxy
 *       does not implement that return type, so the cast fails at runtime.</li>
 *   <li>Implementing {@link DataSource} on the holder makes it the bean: lookups on
 *       {@code DataSource.class} resolve to this class directly, no proxy gymnastics.</li>
 *   <li>{@code @Singleton} (not {@code @ApplicationScoped}) avoids client-proxy generation
 *       altogether, so the cast is the real instance.</li>
 * </ul>
 *
 * <p>The {@link #INSTANCE} static field is the classical pattern for "extension-published
 * singleton": Chappe uses the same approach with {@code ChappeMountPoint.install(...)}. The
 * extension assigns {@link #INSTANCE} during {@code beforeStart}; this holder simply forwards
 * every JDBC call to it.
 */
@Singleton
public class MansartPoolHolder implements DataSource {

    /** Set by {@link MansartPoolExtension#beforeStart} before the container boots. */
    static volatile MansartDataSource INSTANCE;

    private static MansartDataSource delegate() {
        MansartDataSource ds = INSTANCE;
        if (ds == null) {
            throw new IllegalStateException(
                    "MansartPoolExtension did not publish a DataSource — is vidocq.pool.url set?");
        }
        return ds;
    }

    @Override public Connection  getConnection() throws SQLException                                  { return delegate().getConnection(); }
    @Override public Connection  getConnection(String username, String password) throws SQLException  { return delegate().getConnection(username, password); }
    @Override public PrintWriter getLogWriter() throws SQLException                                   { return delegate().getLogWriter(); }
    @Override public void        setLogWriter(PrintWriter out) throws SQLException                    { delegate().setLogWriter(out); }
    @Override public void        setLoginTimeout(int seconds) throws SQLException                     { delegate().setLoginTimeout(seconds); }
    @Override public int         getLoginTimeout() throws SQLException                                { return delegate().getLoginTimeout(); }
    @Override public Logger      getParentLogger()                                                    { return delegate().getParentLogger(); }
    @Override public <T> T       unwrap(Class<T> iface) throws SQLException                           { return delegate().unwrap(iface); }
    @Override public boolean     isWrapperFor(Class<?> iface) throws SQLException                     { return delegate().isWrapperFor(iface); }
}
