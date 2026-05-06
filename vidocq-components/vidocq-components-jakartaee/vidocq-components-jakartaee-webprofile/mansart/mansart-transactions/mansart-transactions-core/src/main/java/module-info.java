/**
 * Local-only Jakarta Transactions 2.0 implementation. Provides
 * {@link jakarta.transaction.TransactionManager},
 * {@link jakarta.transaction.UserTransaction} and
 * {@link jakarta.transaction.TransactionSynchronizationRegistry} bound to the current virtual
 * thread via {@link java.lang.ScopedValue}. 2PC + recovery come later.
 */
module io.vidocq.mansart.transactions.core {
    requires transitive io.vidocq.mansart.transactions.api;

    exports io.vidocq.mansart.transactions.core;
}
