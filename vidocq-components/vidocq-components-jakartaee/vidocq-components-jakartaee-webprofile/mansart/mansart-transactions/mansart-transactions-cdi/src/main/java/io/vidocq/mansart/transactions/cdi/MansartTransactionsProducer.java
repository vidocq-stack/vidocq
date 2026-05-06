package io.vidocq.mansart.transactions.cdi;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.UserTransaction;

/**
 * CDI producer publishing the three Jakarta Transactions singletons backed by a single
 * {@link MansartTransactionManager} instance per JVM.
 *
 * <p>Singleton-per-JVM is the right granularity : the TM holds per-thread state via
 * {@link ThreadLocal}, so sharing one TM across all consumers is correct AND required
 * (otherwise {@code @Transactional} on bean A and bean B see two independent threads-locals).
 *
 * <p>Why a producer rather than a synthetic bean via {@code @Synthesis} BCE :
 * BCE synthetic beans require a {@code SyntheticBeanCreator} which adds non-trivial wiring
 * and works less consistently across CDI implementations (Vauban supports it, Weld supports
 * it, but the equivalent {@code @Produces} is portable to every CDI 4.x container).
 */
@ApplicationScoped
public class MansartTransactionsProducer {

    private static final MansartTransactionManager TM = new MansartTransactionManager();

    /** Same instance the producer publishes — used by {@link MansartTransactionsPortableExtension}
     *  which needs the TM to wire {@link TransactionScopedContext} BEFORE CDI injection is online. */
    static MansartTransactionManager tm() {
        return TM;
    }

    @Produces
    @Singleton
    public TransactionManager transactionManager() {
        return TM;
    }

    @Produces
    @Singleton
    public UserTransaction userTransaction() {
        return new MansartUserTransactionFacade(TM);
    }

    @Produces
    @Singleton
    public TransactionSynchronizationRegistry transactionSynchronizationRegistry() {
        return new MansartTSR(TM);
    }
}
