package io.vidocq.mansart.transactions.tck;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.UserTransaction;

/**
 * Adapter exposed to the official Jakarta Transactions 2.0 TCK harness.
 *
 * <p>The TCK loads this class via the system property
 * {@code jakarta.transaction.TransactionManager.implementation}
 * (configured in pom.xml — {@code tck-run} profile) and asks it for the four singletons every
 * Jakarta Transactions implementation must publish :
 * {@link TransactionManager}, {@link UserTransaction}, {@link TransactionSynchronizationRegistry},
 * and access to the underlying {@code Transaction} instance.
 *
 * <p>Singleton-by-design : the TCK assumes one TM per JVM. Our {@link MansartTransactionManager}
 * is intrinsically thread-safe (per-thread state via {@link ThreadLocal}), so a single instance
 * shared across the whole TCK run is correct.
 */
public final class MansartTckProvider {

    private static final MansartTransactionManager TM = new MansartTransactionManager();

    private MansartTckProvider() {}

    public static TransactionManager getTransactionManager() {
        return TM;
    }

    /**
     * The TCK occasionally asks for a {@link UserTransaction} facade — same underlying TM,
     * narrower API.
     */
    public static UserTransaction getUserTransaction() {
        return new MansartUserTransaction(TM);
    }
}
