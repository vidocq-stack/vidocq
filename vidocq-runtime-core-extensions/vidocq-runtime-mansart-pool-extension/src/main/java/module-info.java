/**
 * Vidocq extension that exposes a {@link io.vidocq.mansart.pool.core.MansartDataSource} as the
 * application's {@code @Default} JDBC {@link javax.sql.DataSource}, configured from
 * {@code vidocq.pool.*} properties.
 */
module io.vidocq.runtime.ext.mansart.pool {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    requires io.vidocq.mansart.pool.api;
    requires io.vidocq.mansart.pool.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires java.sql;        // javax.sql.DataSource

    // CDI scans MansartPoolHolder for @Produces; the package needs to be opened so the
    // generated bean factory can construct it.
    exports io.vidocq.runtime.ext.mansart.pool;
    opens   io.vidocq.runtime.ext.mansart.pool;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.ext.mansart.pool.MansartPoolExtension;
}
