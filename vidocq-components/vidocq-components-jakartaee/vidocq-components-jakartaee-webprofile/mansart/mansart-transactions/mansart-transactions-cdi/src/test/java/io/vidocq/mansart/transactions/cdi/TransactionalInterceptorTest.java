package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionalException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bootstraps Weld SE in-process and verifies the {@link TransactionalInterceptor} for the six
 * {@link Transactional.TxType} values, plus rollback rules.
 */
class TransactionalInterceptorTest {

    private WeldContainer container;

    @BeforeEach
    void setUp() {
        container = new Weld()
                .disableDiscovery()
                .addBeanClasses(
                        MansartTransactionsProducer.class,
                        TransactionalInterceptor.class,
                        TransactionalInterceptorRequiresNew.class,
                        TransactionalInterceptorMandatory.class,
                        TransactionalInterceptorNever.class,
                        TransactionalInterceptorNotSupported.class,
                        TransactionalInterceptorSupports.class,
                        TxService.class)
                // Enable all six interceptor bindings explicitly. One subclass per TxType is
                // required because Transactional.value() is NOT @Nonbinding in jakarta.transaction-api 2.0.x.
                .interceptors(
                        TransactionalInterceptor.class,
                        TransactionalInterceptorRequiresNew.class,
                        TransactionalInterceptorMandatory.class,
                        TransactionalInterceptorNever.class,
                        TransactionalInterceptorNotSupported.class,
                        TransactionalInterceptorSupports.class)
                .initialize();
    }

    @AfterEach
    void tearDown() {
        // Roll back any TX leaked by a failing test — the TM's ThreadLocal survives across
        // test methods on the same JUnit-Jupiter thread.
        try {
            if (container != null && tm().getStatus() != Status.STATUS_NO_TRANSACTION) {
                tm().rollback();
            }
        } catch (Exception ignored) { /* best effort */ }
        if (container != null) container.shutdown();
    }

    private TxService svc() { return container.select(TxService.class).get(); }
    private TransactionManager tm() { return container.select(TransactionManager.class).get(); }
    private UserTransaction ut() { return container.select(UserTransaction.class).get(); }

    // ============ REQUIRED ============

    @Test
    void requiredStartsTxWhenNoneActive() throws Exception {
        assertThat(tm().getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        svc().required(() -> assertThat(tm().getStatus()).isEqualTo(Status.STATUS_ACTIVE));
        assertThat(tm().getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void requiredJoinsActiveTx() throws Exception {
        ut().begin();
        try {
            int outerHash = System.identityHashCode(tm().getTransaction());
            svc().required(() -> assertThat(System.identityHashCode(unsafeTx())).isEqualTo(outerHash));
            assertThat(tm().getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        } finally {
            ut().commit();
        }
    }

    // ============ REQUIRES_NEW ============

    @Test
    void requiresNewSuspendsAndResumes() throws Exception {
        ut().begin();
        int outerHash = System.identityHashCode(tm().getTransaction());
        svc().requiresNew(() -> {
            int innerHash = System.identityHashCode(unsafeTx());
            assertThat(innerHash).isNotEqualTo(outerHash);
        });
        assertThat(System.identityHashCode(tm().getTransaction())).isEqualTo(outerHash);
        ut().commit();
    }

    // ============ MANDATORY ============

    @Test
    void mandatoryFailsWithoutActiveTx() {
        assertThatThrownBy(() -> svc().mandatory(() -> {}))
                .isInstanceOf(TransactionalException.class);
    }

    @Test
    void mandatorySucceedsWithActiveTx() throws Exception {
        ut().begin();
        try {
            svc().mandatory(() -> assertThat(tm().getStatus()).isEqualTo(Status.STATUS_ACTIVE));
        } finally {
            ut().commit();
        }
    }

    // ============ NEVER ============

    @Test
    void neverFailsWithActiveTx() throws Exception {
        ut().begin();
        try {
            assertThatThrownBy(() -> svc().never(() -> {}))
                    .isInstanceOf(TransactionalException.class);
        } finally {
            ut().commit();
        }
    }

    @Test
    void neverSucceedsWithoutActiveTx() throws Exception {
        svc().never(() -> assertThat(tm().getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION));
    }

    // ============ NOT_SUPPORTED ============

    @Test
    void notSupportedSuspendsActiveTx() throws Exception {
        ut().begin();
        svc().notSupported(() -> assertThat(tm().getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION));
        assertThat(tm().getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        ut().commit();
    }

    // ============ SUPPORTS ============

    @Test
    void supportsRunsWithActiveTx() throws Exception {
        ut().begin();
        try {
            svc().supports(() -> assertThat(tm().getStatus()).isEqualTo(Status.STATUS_ACTIVE));
        } finally {
            ut().commit();
        }
    }

    @Test
    void supportsRunsWithoutActiveTx() throws Exception {
        svc().supports(() -> assertThat(tm().getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION));
    }

    // ============ Rollback rules ============

    @Test
    void runtimeExceptionRollsBackByDefault() {
        assertThatThrownBy(() -> svc().required(() -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
        assertThat(unsafeStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void checkedExceptionDoesNotRollBackByDefault() {
        assertThatThrownBy(() -> svc().required(() -> { throw new java.io.IOException("io"); }))
                .isInstanceOf(java.io.IOException.class);
        // Defaut : checked → commit, no rollback ; status returns to NO_TRANSACTION after commit.
        assertThat(unsafeStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void rollbackOnTriggersForCheckedException() {
        assertThatThrownBy(() -> svc().requiredRollbackOnIO(
                () -> { throw new java.io.IOException("io"); }))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void dontRollbackOnSuppressesRuntimeRollback() {
        assertThatThrownBy(() -> svc().requiredDontRollbackOnIllegalState(
                () -> { throw new IllegalStateException("ok"); }))
                .isInstanceOf(IllegalStateException.class);
        // dontRollbackOn dominated → no rollback → commit → NO_TRANSACTION after.
        assertThat(unsafeStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    private jakarta.transaction.Transaction unsafeTx() {
        try { return tm().getTransaction(); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private int unsafeStatus() {
        try { return tm().getStatus(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ============ Test bean ============

    @ApplicationScoped
    public static class TxService {

        @Inject TransactionManager tm;

        @Transactional(Transactional.TxType.REQUIRED)
        public void required(Block b) throws Exception { b.run(); }

        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public void requiresNew(Block b) throws Exception { b.run(); }

        @Transactional(Transactional.TxType.MANDATORY)
        public void mandatory(Block b) throws Exception { b.run(); }

        @Transactional(Transactional.TxType.NEVER)
        public void never(Block b) throws Exception { b.run(); }

        @Transactional(Transactional.TxType.NOT_SUPPORTED)
        public void notSupported(Block b) throws Exception { b.run(); }

        @Transactional(Transactional.TxType.SUPPORTS)
        public void supports(Block b) throws Exception { b.run(); }

        @Transactional(value = Transactional.TxType.REQUIRED, rollbackOn = java.io.IOException.class)
        public void requiredRollbackOnIO(Block b) throws Exception { b.run(); }

        @Transactional(value = Transactional.TxType.REQUIRED, dontRollbackOn = IllegalStateException.class)
        public void requiredDontRollbackOnIllegalState(Block b) throws Exception { b.run(); }
    }

    @FunctionalInterface public interface Block { void run() throws Exception; }
}
