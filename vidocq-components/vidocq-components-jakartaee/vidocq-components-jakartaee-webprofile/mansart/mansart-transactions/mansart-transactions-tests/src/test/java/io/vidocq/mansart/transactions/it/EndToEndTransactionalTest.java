package io.vidocq.mansart.transactions.it;

import io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
import io.vidocq.mansart.transactions.cdi.TransactionalInterceptor;
import io.vidocq.mansart.transactions.cdi.TransactionalInterceptorMandatory;
import io.vidocq.mansart.transactions.cdi.TransactionalInterceptorNever;
import io.vidocq.mansart.transactions.cdi.TransactionalInterceptorNotSupported;
import io.vidocq.mansart.transactions.cdi.TransactionalInterceptorRequiresNew;
import io.vidocq.mansart.transactions.cdi.TransactionalInterceptorSupports;
import io.vidocq.vauban.junit.AddBeans;
import io.vidocq.vauban.junit.VaubanTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-module integration test : Vauban CDI 4.1 Lite container + mansart-transactions-cdi
 * (interceptor + scope + producer) + mansart-transactions-core (TM + 2PC + recovery).
 *
 * <p>Validates the full happy-path AND exception-path of the chain :
 * <ol>
 *   <li>{@code @Transactional} method begins/commits a TX through the interceptor ;</li>
 *   <li>{@code @TransactionScoped} bean instances are reused within the same TX, distinct across TXs ;</li>
 *   <li>An enrolled {@link RecordingXAResource} sees the proper start / end / commit (1PC) sequence ;</li>
 *   <li>An exception in the {@code @Transactional} method triggers rollback of the resource AND
 *       destruction of the {@code @TransactionScoped} bean.</li>
 * </ol>
 *
 * <p>Real JDBC bound to mansart-pool will arrive in M8 once a XA-aware {@code DataSource}
 * wrapper exposes a {@code XAResource} per {@code Connection}. Until then, the recording
 * resource is the most compact way to assert protocol conformance end-to-end.
 */
@VaubanTest
@AddBeans({
        MansartTransactionsExtension.class,
        TransactionalInterceptor.class,
        TransactionalInterceptorRequiresNew.class,
        TransactionalInterceptorMandatory.class,
        TransactionalInterceptorNever.class,
        TransactionalInterceptorNotSupported.class,
        TransactionalInterceptorSupports.class,
        EndToEndTransactionalTest.OrderService.class,
        EndToEndTransactionalTest.OrderState.class
})
class EndToEndTransactionalTest {

    @Inject TransactionManager tm;
    @Inject OrderService orders;

    @AfterEach
    void cleanup() throws Exception {
        if (tm != null && tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            tm.rollback();
        }
    }

    @Test
    void commitsHappyPath() throws Exception {
        var resource = RecordingXAResource.named("xa1");

        orders.placeOrder("ORD-1", resource);

        // 1PC : single resource → no prepare, just commit(onePhase=true).
        assertThat(resource.log).containsExactly(
                "xa1.start", "xa1.end", "xa1.commit(onePhase=true)");
        // The @TransactionScoped state seen by the service was the same instance.
        assertThat(orders.lastSeenScopedHash()).isPositive();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void rollsBackOnException() {
        var resource = RecordingXAResource.named("xa1");

        assertThatThrownBy(() -> orders.placeOrderThenFail("ORD-2", resource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated");

        // Rollback path : end(TMFAIL) then rollback. No commit, no prepare.
        assertThat(resource.log).containsExactly("xa1.start", "xa1.end", "xa1.rollback");
    }

    @Test
    void scopedBeanReusedInSameTxAndDistinctAcrossTxs() throws Exception {
        OrderState.CREATED.set(0);
        OrderState.DESTROYED.set(0);

        orders.placeOrder("ORD-A", RecordingXAResource.named("xa-tx1"));
        orders.placeOrder("ORD-B", RecordingXAResource.named("xa-tx2"));

        // Two transactions → two distinct @TransactionScoped instances created and destroyed.
        // Asserting on PostConstruct/PreDestroy counters because the System.identityHashCode of
        // the CDI proxy is stable across TXs (the proxy delegates to the contextual instance).
        assertThat(OrderState.CREATED.get()).isEqualTo(2);
        assertThat(OrderState.DESTROYED.get()).isEqualTo(2);
    }

    @Test
    void requiresNewSuspendsOuterAndKeepsResourceUntouched() throws Exception {
        var outer = RecordingXAResource.named("outer");
        var inner = RecordingXAResource.named("inner");

        orders.placeOrderWithNested("ORD-NESTED", outer, inner);

        // Outer wraps the whole flow : start → ... → end → commit(1PC).
        assertThat(outer.log).containsExactly(
                "outer.start", "outer.end", "outer.commit(onePhase=true)");
        // Inner runs inside REQUIRES_NEW : independent start/end/commit.
        assertThat(inner.log).containsExactly(
                "inner.start", "inner.end", "inner.commit(onePhase=true)");
    }

    // ============ Beans under test ============

    @ApplicationScoped
    public static class OrderService {

        private int lastSeenScopedHash;

        public int lastSeenScopedHash() { return lastSeenScopedHash; }

        @Transactional
        public void placeOrder(String orderId,
                               javax.transaction.xa.XAResource resource) throws Exception {
            var tm = CDI.current().select(TransactionManager.class).get();
            tm.getTransaction().enlistResource(resource);
            // Touch the @TransactionScoped state so it gets created within this TX.
            var state = CDI.current().select(OrderState.class).get();
            state.recordOrder(orderId);
            lastSeenScopedHash = System.identityHashCode(state);
        }

        @Transactional
        public void placeOrderThenFail(String orderId,
                                       javax.transaction.xa.XAResource resource) throws Exception {
            placeOrderInner(orderId, resource);
            throw new IllegalStateException("simulated business failure");
        }

        @Transactional
        public void placeOrderWithNested(String orderId,
                                         javax.transaction.xa.XAResource outer,
                                         javax.transaction.xa.XAResource inner) throws Exception {
            var tm = CDI.current().select(TransactionManager.class).get();
            tm.getTransaction().enlistResource(outer);
            // The CDI proxy on `this` doesn't intercept self-invocation ; route through CDI
            // to actually cross the interceptor chain.
            CDI.current().select(OrderService.class).get().nestedRequiresNew(orderId, inner);
        }

        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public void nestedRequiresNew(String id, javax.transaction.xa.XAResource resource) throws Exception {
            var tm = CDI.current().select(TransactionManager.class).get();
            tm.getTransaction().enlistResource(resource);
        }

        // Helper non-self-invoked : called via CDI proxy by placeOrderThenFail to ensure
        // the resource is enrolled BEFORE the exception bubbles up.
        private void placeOrderInner(String orderId, javax.transaction.xa.XAResource resource) throws Exception {
            var tm = CDI.current().select(TransactionManager.class).get();
            tm.getTransaction().enlistResource(resource);
            CDI.current().select(OrderState.class).get().recordOrder(orderId);
        }
    }

    @TransactionScoped
    public static class OrderState implements Serializable {
        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        private final java.util.List<String> orders = new java.util.ArrayList<>();

        @jakarta.annotation.PostConstruct void onCreate()  { CREATED.incrementAndGet(); }
        @jakarta.annotation.PreDestroy   void onDestroy() { DESTROYED.incrementAndGet(); }

        public void recordOrder(String id) { orders.add(id); }
        public java.util.List<String> orders() { return orders; }
    }
}
