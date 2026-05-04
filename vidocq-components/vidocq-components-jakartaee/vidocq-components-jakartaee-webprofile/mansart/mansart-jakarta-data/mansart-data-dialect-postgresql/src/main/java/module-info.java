/**
 * PostgreSQL dialect for Mansart Data. Implementation in M4.
 */
module io.vidocq.mansart.data.dialect.postgresql {
    requires io.vidocq.mansart.data.dialect.spi;
    requires io.vidocq.mansart.data.core;   // for MansartDataException in translate()
    requires java.sql;

    exports io.vidocq.mansart.data.dialect.postgresql;

    provides io.vidocq.mansart.data.dialect.DialectFactory
            with io.vidocq.mansart.data.dialect.postgresql.PostgresqlDialectFactory;
}
