package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.transaction.UserTransaction;

/**
 * Synthetic-bean creator publishing the default {@link UserTransaction} facade backed by
 * the shared TM held in {@link MansartTransactionsProducer}.
 */
public final class DefaultUserTransactionCreator implements SyntheticBeanCreator<UserTransaction> {

    @Override
    public UserTransaction create(Instance<Object> lookup, Parameters params) {
        return MansartTransactionsProducer.userTransaction();
    }
}
