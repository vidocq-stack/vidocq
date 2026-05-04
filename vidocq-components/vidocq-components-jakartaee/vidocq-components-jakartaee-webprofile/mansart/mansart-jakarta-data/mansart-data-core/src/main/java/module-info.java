/**
 * Runtime engine for Mansart Data — bootstrap, repository runtime, connection scope.
 * Filled in M3.
 */
module io.vidocq.mansart.data.core {
    requires transitive io.vidocq.mansart.data.dialect.spi;
    requires java.sql;
    // jakarta.transaction will be re-added in M3b once the runtime uses UserTransaction
    // (its module-info has `requires static jakarta.cdi`, which drags jakarta.cdi at compile time).

    exports io.vidocq.mansart.data.core;

    uses io.vidocq.mansart.data.dialect.DialectFactory;
}
