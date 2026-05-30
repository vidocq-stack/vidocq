package io.vidocq.mansart.transactions.tck;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the Mansart TCK harness. Verifies that the provider exposes the expected
 * singletons and that a minimal begin/commit cycle succeeds. This is the equivalent of the
 * "assert wiring works" check that the official TCK runs first; keeping it outside the full suite
 * avoids running the entire TCK just to validate a provider regression.
 *
 * <p>Executed by default with {@code mvn test} (default profile,
 * includes=MansartTckSmoke*). The {@code tck-run} profile additionally enables official TCK jar
 * scanning.
 */
class MansartTckSmokeTest {

    @Test
    void providerExposesNonNullTransactionManager() {
        TransactionManager tm = MansartTckProvider.getTransactionManager();
        assertThat(tm).isNotNull();
    }

    @Test
    void providerExposesNonNullUserTransaction() {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        assertThat(ut).isNotNull();
    }

    @Test
    void userTransactionBeginCommitCycle() throws Exception {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        ut.begin();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        ut.commit();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void userTransactionRollbackCycle() throws Exception {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        ut.begin();
        ut.rollback();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void singletonAcrossLookups() {
        // The TCK often makes N calls — the provider must return the same TM (otherwise
        // the ThreadLocal for active state doesn't work across phases).
        assertThat(MansartTckProvider.getTransactionManager())
                .isSameAs(MansartTckProvider.getTransactionManager());
    }
}
