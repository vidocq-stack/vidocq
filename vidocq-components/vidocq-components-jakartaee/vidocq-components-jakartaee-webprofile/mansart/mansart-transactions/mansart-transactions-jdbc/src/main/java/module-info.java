/**
 * JDBC adapter for Mansart Transactions — wraps a plain {@link java.sql.Connection} as an
 * {@link javax.transaction.xa.XAResource} so it can participate in a Mansart-coordinated
 * transaction without requiring a {@code XADataSource} driver.
 *
 * <p>Limitation : 1PC only — multi-resource 2PC requires a real XA driver.
 */
module io.vidocq.mansart.transactions.jdbc {
    requires transitive io.vidocq.mansart.transactions.core;
    requires java.sql;
    requires java.transaction.xa;

    exports io.vidocq.mansart.transactions.jdbc;
}
