/**
 * Runtime for Mansart Pool. Exposes {@code MansartDataSource} as a {@link javax.sql.DataSource}.
 */
module io.vidocq.mansart.pool.core {
    requires transitive io.vidocq.mansart.pool.api;
    requires java.sql;          // javax.sql.DataSource, java.sql.Connection, DriverManager

    exports io.vidocq.mansart.pool.core;
}
