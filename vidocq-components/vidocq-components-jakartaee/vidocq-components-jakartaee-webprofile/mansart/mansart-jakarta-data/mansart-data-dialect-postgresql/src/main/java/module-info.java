/**
 * PostgreSQL dialect for Mansart Data. Implementation in M4.
 */
module io.vidocq.mansart.data.dialect.postgresql {
    requires io.vidocq.mansart.data.dialect.spi;
    requires java.sql;

    exports io.vidocq.mansart.data.dialect.postgresql;

    provides io.vidocq.mansart.data.dialect.DialectFactory
            with io.vidocq.mansart.data.dialect.postgresql.PostgresqlDialectFactory;
}
