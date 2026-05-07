package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.transaction.TransactionManager;

/**
 * Synthetic-bean creator that publishes the default {@link TransactionManager}, backed by the
 * shared {@link MansartTransactionsProducer#tm()} singleton-per-JVM.
 *
 * <p>Wired by {@link MansartTransactionsExtension} via {@code @Synthesis components.addBean(...)}
 * — see the rationale on {@link MansartTransactionsProducer} for why a synthetic bean rather
 * than a real {@code @Produces} class is used here (JPMS split-package avoidance).
 */
public final class DefaultTransactionManagerCreator implements SyntheticBeanCreator<TransactionManager> {

    @Override
    public TransactionManager create(Instance<Object> lookup, Parameters params) {
        return MansartTransactionsProducer.transactionManager();
    }
}
