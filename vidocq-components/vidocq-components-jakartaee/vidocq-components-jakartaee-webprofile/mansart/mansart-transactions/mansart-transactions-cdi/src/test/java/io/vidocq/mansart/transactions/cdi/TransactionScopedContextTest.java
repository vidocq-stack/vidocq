package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.transaction.TransactionScoped;
import jakarta.transaction.UserTransaction;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link TransactionScopedContext} keeps a per-transaction instance and
 * destroys it at completion.
 */
class TransactionScopedContextTest {

    static final AtomicInteger CREATED = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    private WeldContainer container;

    @BeforeEach
    void setUp() {
        CREATED.set(0);
        DESTROYED.set(0);
        container = new Weld()
                .disableDiscovery()
                .addExtensions(MansartTransactionsPortableExtension.class)
                .addBeanClasses(MansartTransactionsProducer.class, TxBean.class)
                .initialize();
    }

    @AfterEach
    void tearDown() {
        try {
            if (container != null) {
                var tm = container.select(jakarta.transaction.TransactionManager.class).get();
                if (tm.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                    tm.rollback();
                }
            }
        } catch (Exception ignored) { /* best effort */ }
        if (container != null) container.shutdown();
    }

    @Test
    void scopeIsInactiveOutsideTx() {
        // No active TX → the context's get() must refuse to resolve the bean.
        assertThatThrownBy(() -> container.select(TxBean.class).get().bump())
                .isInstanceOf(ContextNotActiveException.class);
    }

    @Test
    void sameInstanceWithinSingleTx() throws Exception {
        UserTransaction ut = container.select(UserTransaction.class).get();
        ut.begin();
        try {
            TxBean a = container.select(TxBean.class).get();
            TxBean b = container.select(TxBean.class).get();
            a.bump();
            b.bump();
            assertThat(a.value()).isEqualTo(2);
            assertThat(b.value()).isEqualTo(2);  // same proxy => same backing instance
            assertThat(CREATED.get()).isEqualTo(1);
        } finally {
            ut.commit();
        }
        assertThat(DESTROYED.get()).isEqualTo(1);
    }

    @Test
    void distinctInstanceAcrossTxs() throws Exception {
        UserTransaction ut = container.select(UserTransaction.class).get();
        ut.begin();
        TxBean first = container.select(TxBean.class).get();
        first.bump();
        ut.commit();

        ut.begin();
        TxBean second = container.select(TxBean.class).get();
        // value() reads through the proxy → resolves to the SECOND tx's instance, value should be 0
        assertThat(second.value()).isEqualTo(0);
        ut.commit();
        assertThat(CREATED.get()).isEqualTo(2);
        assertThat(DESTROYED.get()).isEqualTo(2);
    }

    @Test
    void destroyOnRollback() throws Exception {
        UserTransaction ut = container.select(UserTransaction.class).get();
        ut.begin();
        TxBean b = container.select(TxBean.class).get();
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
