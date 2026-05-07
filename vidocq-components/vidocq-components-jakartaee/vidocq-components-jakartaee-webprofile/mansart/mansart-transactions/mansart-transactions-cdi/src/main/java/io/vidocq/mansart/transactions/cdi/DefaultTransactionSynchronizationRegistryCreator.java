package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Synthetic-bean creator publishing the default {@link TransactionSynchronizationRegistry}
 * facade backed by the shared TM held in {@link MansartTransactionsProducer}.
 */
public final class DefaultTransactionSynchronizationRegistryCreator
        implements SyntheticBeanCreator<TransactionSynchronizationRegistry> {

    @Override
    public TransactionSynchronizationRegistry create(Instance<Object> lookup, Parameters params) {
        return MansartTransactionsProducer.transactionSynchronizationRegistry();
    }
}
