package io.vidocq.mansart.transactions.cdi;

import io.vidocq.vauban.junit.AddBeans;
import io.vidocq.vauban.junit.VaubanTest;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link TransactionScopedContext} keeps a per-transaction instance and destroys
 * it at completion — bootstrapped through Vauban (CDI 4.1 Lite) and the BCE
 * {@link MansartTransactionsExtension} which registers the scope via
 * {@code MetaAnnotations.addContext}.
 */
@VaubanTest
@AddBeans({
        MansartTransactionsProducer.class,
        MansartTransactionsExtension.class,
        TransactionScopedContextTest.TxBean.class
})
class TransactionScopedContextTest {

    static final AtomicInteger CREATED = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    @Inject UserTransaction ut;
    @Inject TransactionManager tm;

    private TxBean lookup() {
        // VaubanExtension.injectFields() doesn't yet resolve Instance<T> generics, so we go
        // through the standard CDI lookup which Vauban implements correctly.
        return CDI.current().select(TxBean.class).get();
    }

    @BeforeEach
    void resetCounters() {
        CREATED.set(0);
        DESTROYED.set(0);
    }

    @AfterEach
    void cleanup() {
        try {
            if (tm != null && tm.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                tm.rollback();
            }
        } catch (Exception ignored) { /* best effort */ }
    }

    @Test
    void scopeIsInactiveOutsideTx() {
        assertThatThrownBy(() -> lookup().bump())
                .isInstanceOf(ContextNotActiveException.class);
    }

    @Test
    void sameInstanceWithinSingleTx() throws Exception {
        ut.begin();
        try {
            TxBean a = lookup();
            TxBean b = lookup();
            a.bump();
            b.bump();
            assertThat(a.value()).isEqualTo(2);
            assertThat(b.value()).isEqualTo(2);
            assertThat(CREATED.get()).isEqualTo(1);
        } finally {
            ut.commit();
        }
        assertThat(DESTROYED.get()).isEqualTo(1);
    }

    @Test
    void distinctInstanceAcrossTxs() throws Exception {
        ut.begin();
        TxBean first = lookup();
        first.bump();
        ut.commit();

        ut.begin();
        TxBean second = lookup();
        assertThat(second.value()).isEqualTo(0);
        ut.commit();
        assertThat(CREATED.get()).isEqualTo(2);
        assertThat(DESTROYED.get()).isEqualTo(2);
    }

    @Test
    void destroyOnRollback() throws Exception {
        ut.begin();
        TxBean b = lookup();
        b.bump();
        ut.rollback();
        assertThat(DESTROYED.get()).isEqualTo(1);
    }

    @TransactionScoped
    public static class TxBean implements Serializable {
        private int counter;

        @jakarta.annotation.PostConstruct
        void onCreate() { CREATED.incrementAndGet(); }

        @jakarta.annotation.PreDestroy
        void onDestroy() { DESTROYED.incrementAndGet(); }

        public void bump() { counter++; }
        public int value() { return counter; }
    }
}
