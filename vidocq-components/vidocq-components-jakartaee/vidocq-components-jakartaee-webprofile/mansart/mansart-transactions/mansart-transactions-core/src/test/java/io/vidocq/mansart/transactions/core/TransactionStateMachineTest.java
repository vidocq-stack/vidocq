package io.vidocq.mansart.transactions.core;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State-machine guarantees expected from the M1 {@link MansartTransactionManager} :
 * setRollbackOnly behavior, illegal transitions raising IllegalStateException, and the
 * "no-active-tx" error path on commit/rollback/setRollbackOnly.
 */
class TransactionStateMachineTest {

    private final TransactionManager tm = new MansartTransactionManager();

    @AfterEach
    void cleanup() throws Exception {
        // Defensive — guarantee no TX leaks between tests if an assertion fails mid-test.
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            try { tm.rollback(); } catch (Exception _) { /* best effort */ }
        }
    }

    @Test
    void setRollbackOnlyMarksTransaction() throws Exception {
        tm.begin();
        tm.setRollbackOnly();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        tm.rollback();
    }

    @Test
    void commitOnMarkedRollbackThrowsRollbackException() throws Exception {
        tm.begin();
        tm.setRollbackOnly();
        assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class);
        // After the failed commit, the TX is closed and the thread is back to NO_TRANSACTION.
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void commitWithoutBeginIsIllegalState() {
        assertThatThrownBy(tm::commit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollbackWithoutBeginIsIllegalState() {
        assertThatThrownBy(tm::rollback).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setRollbackOnlyWithoutBeginIsIllegalState() {
        assertThatThrownBy(tm::setRollbackOnly).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitTwiceFails() throws Exception {
        tm.begin();
        tm.commit();
        assertThatThrownBy(tm::commit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollbackAfterCommitFails() throws Exception {
        tm.begin();
        tm.commit();
        assertThatThrownBy(tm::rollback).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getTransactionInsideTxExposesActiveTransaction() throws Exception {
        tm.begin();
        var tx = tm.getTransaction();
        assertThat(tx).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        tm.rollback();
        assertThat(tm.getTransaction()).isNull();
    }

    @Test
    void perThreadIsolation() throws Exception {
        tm.begin();
        // Another thread (platform or virtual) starts with no active transaction.
        var fromOther = new int[]{Status.STATUS_UNKNOWN};
        var t = Thread.ofVirtual().start(() -> {
            try { fromOther[0] = tm.getStatus(); }
            catch (Exception e) { fromOther[0] = -1; }
        });
        t.join();
        assertThat(fromOther[0]).isEqualTo(Status.STATUS_NO_TRANSACTION);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        tm.rollback();
    }

    @Test
    void negativeTimeoutRejected() {
        assertThatThrownBy(() -> tm.setTransactionTimeout(-1))
                .isInstanceOf(jakarta.transaction.SystemException.class);
    }
}
