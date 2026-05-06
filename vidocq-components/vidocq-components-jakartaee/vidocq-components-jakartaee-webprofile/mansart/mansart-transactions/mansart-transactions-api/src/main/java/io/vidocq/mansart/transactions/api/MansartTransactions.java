package io.vidocq.mansart.transactions.api;

/**
 * Marker / facade class for the public Mansart Transactions SPI. Holds nothing for the moment —
 * the bootstrap and {@link jakarta.transaction.TransactionManager} are exposed through CDI
 * (see {@code mansart-transactions-cdi}) or via {@link java.util.ServiceLoader} (see
 * {@code mansart-transactions-core}). Will grow with helper hooks (resource enrolment,
 * synchronisations factory, recovery callbacks) as the implementation matures.
 */
public final class MansartTransactions {
    private MansartTransactions() {}
}
