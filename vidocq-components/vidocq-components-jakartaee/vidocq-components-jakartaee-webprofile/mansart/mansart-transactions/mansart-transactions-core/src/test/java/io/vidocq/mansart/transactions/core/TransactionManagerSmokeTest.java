package io.vidocq.mansart.transactions.core;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 — TDD seed for {@link MansartTransactionManager}. Every assertion currently fails because
 * the implementation is a skeleton. Drive the implementation by making each test go green in
 * order ; commit per test, refactor between.
 */
class TransactionManagerSmokeTest {

    private final TransactionManager tm = new MansartTransactionManager();

    @Test
    void newTransactionManagerHasNoActiveTransaction() throws Exception {
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        assertThat(tm.getTransaction()).isNull();
    }

    @Test
    void beginActivatesTransaction() throws Exception {
        tm.begin();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        assertThat(tm.getTransaction()).isNotNull();

        // Cleanup so other tests are not polluted by a lingering active TX on this thread.
        tm.rollback();
    }

    @Test
    void commitClearsTransaction() throws Exception {
        tm.begin();
        tm.commit();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        assertThat(tm.getTransaction()).isNull();
    }

    @Test
    void rollbackClearsTransaction() throws Exception {
        tm.begin();
        tm.rollback();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void beginTwiceFails() throws Exception {
        tm.begin();
        try {
            assertThatThrownBy(tm::begin)
                    .isInstanceOf(jakarta.transaction.NotSupportedException.class);
        } finally {
            tm.rollback();
        }
    }
}
