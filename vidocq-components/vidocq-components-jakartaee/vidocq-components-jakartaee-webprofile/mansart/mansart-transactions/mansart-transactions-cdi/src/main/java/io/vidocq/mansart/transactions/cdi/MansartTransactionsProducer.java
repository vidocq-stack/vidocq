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
 * <p>Singleton-per-JVM is the right granularity: the TM holds per-thread state via
 * {@link ThreadLocal}, so sharing one TM across all consumers is correct AND required
 * (otherwise {@code @Transactional} on bean A and bean B see two independent thread-locals).
 *
 * <p>Discovered by {@link MansartTransactionsExtension} via
 * {@code ScannedClasses.add(...)} at {@code @Discovery}. Vauban-processor indexes the class
 * for validation but does not emit a {@code *_Factory.class} in the user module's output —
 * the class lives in this jar and Vauban-runtime falls back to a reflective factory. The
 * {@code @Produces} methods on this class are then scanned normally and the three transactions
 * beans become available for {@code @Inject}.
 */
@ApplicationScoped
public class MansartTransactionsProducer {

    private static final MansartTransactionManager TM = new MansartTransactionManager();

    /** Same instance the producer publishes — used by {@link TransactionScopedContext} which
     *  needs the TM <i>before</i> CDI injection is online to resolve the
     *  {@code @TransactionScoped} scope. */
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
