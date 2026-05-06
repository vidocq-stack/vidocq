/**
 * Public SPI of Mansart Transactions — re-exposes Jakarta Transactions 2.0 contracts and adds
 * Mansart-specific extension points (resource enrolment helpers, lifecycle hooks).
 */
module io.vidocq.mansart.transactions.api {
    requires transitive jakarta.transaction;
    // Jakarta Transactions 2.0 module-info itself requires these — re-export so consumers
    // (mansart-transactions-core, user apps) don't need to redeclare them.
    requires transitive jakarta.cdi;
    requires transitive jakarta.interceptor;

    exports io.vidocq.mansart.transactions.api;
}
