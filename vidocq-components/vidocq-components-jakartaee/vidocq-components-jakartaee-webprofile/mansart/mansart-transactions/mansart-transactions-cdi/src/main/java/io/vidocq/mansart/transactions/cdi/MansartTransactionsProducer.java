package io.vidocq.mansart.transactions.cdi;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.UserTransaction;

/**
 * Static holder for the three Jakarta Transactions singletons backed by a single
 * {@link MansartTransactionManager} instance per JVM.
 *
 * <p>Singleton-per-JVM is the right granularity: the TM holds per-thread state via
 * {@link ThreadLocal}, so sharing one TM across all consumers is correct AND required
 * (otherwise {@code @Transactional} on bean A and bean B see two independent thread-locals).
 *
 * <p>Why a holder rather than a {@code @Produces} CDI bean: a real producer class shipped
 * inside this jar would force compile-time bean processors (Vauban) running on the user
 * module to either add it via {@code ScannedClasses.add(...)} (which generates a
 * {@code *_Factory.class} in this package within the user module's output, causing a
 * JPMS split-package) or skip the scan entirely (leaving {@code @Inject TransactionManager}
 * unsatisfied at build time). The synthetic-bean route exposed by {@link MansartTransactionsExtension}
 * delegates to the static accessors below, which keeps the runtime instances unique without
 * any class-on-disk in the user module.
 *
 * <p>The {@code tm()} accessor is also used by {@link TransactionScopedContext}, which needs
 * the TM <i>before</i> CDI injection is online to resolve the {@code @TransactionScoped} scope.
 */
public final class MansartTransactionsProducer {

    private static final MansartTransactionManager TM = new MansartTransactionManager();

    private MansartTransactionsProducer() {
        // Utility class — instances must not exist.
    }

    /**
     * The single {@link MansartTransactionManager} for the current JVM.
     * <p>Used by {@link TransactionScopedContext} (resolves the scope before CDI is online),
     * by {@link DefaultTransactionManagerCreator} (synthetic-bean producer), and by tests.
     */
    public static MansartTransactionManager tm() {
        return TM;
    }

    /** Default {@link TransactionManager} bean handed out by the synthetic-bean creator. */
    public static TransactionManager transactionManager() {
        return TM;
    }

    /** Default {@link UserTransaction} bean handed out by the synthetic-bean creator. */
    public static UserTransaction userTransaction() {
        return new MansartUserTransactionFacade(TM);
    }

    /** Default {@link TransactionSynchronizationRegistry} bean handed out by the synthetic-bean creator. */
    public static TransactionSynchronizationRegistry transactionSynchronizationRegistry() {
        return new MansartTSR(TM);
    }
}
