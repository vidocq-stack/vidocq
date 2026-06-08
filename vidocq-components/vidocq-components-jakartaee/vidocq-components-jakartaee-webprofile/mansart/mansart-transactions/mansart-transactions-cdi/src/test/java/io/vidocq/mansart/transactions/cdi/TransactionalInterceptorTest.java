/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.transactions.cdi;

import io.vidocq.vauban.junit.AddBeans;
import io.vidocq.vauban.junit.VaubanTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionalException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bootstraps a Vauban CDI 4.1 container in-process and verifies the {@link TransactionalInterceptor}
 * for the six {@link Transactional.TxType} values + rollback rules.
 *
 * <p>One subclass per TxType is required because {@code Transactional.value()} is NOT
 * {@code @Nonbinding} in jakarta.transaction-api 2.0.x — CDI considers each TxType as a distinct
 * binding, so a single {@code @Transactional} interceptor only matches REQUIRED.
 */
@VaubanTest
@AddBeans({
        MansartTransactionsProducer.class,
        TransactionalInterceptor.class,
        TransactionalInterceptorRequiresNew.class,
        TransactionalInterceptorMandatory.class,
        TransactionalInterceptorNever.class,
        TransactionalInterceptorNotSupported.class,
        TransactionalInterceptorSupports.class,
        TransactionalInterceptorTest.TxService.class
})
class TransactionalInterceptorTest {

    @Inject TransactionManager tm;
    @Inject UserTransaction ut;
    @Inject TxService svc;

    @AfterEach
    void cleanup() {
        // Roll back any TX leaked by a failing test — the TM's ThreadLocal survives across
        // test methods on the same JUnit-Jupiter thread.
        try {
            if (tm != null && tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
                tm.rollback();
            }
        } catch (Exception ignored) { /* best effort */ }
    }

    // ============ REQUIRED ============

    @Test
    void requiredStartsTxWhenNoneActive() throws Exception {
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        svc.required(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE));
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void requiredJoinsActiveTx() throws Exception {
        ut.begin();
        try {
            int outerHash = System.identityHashCode(tm.getTransaction());
            svc.required(() -> assertThat(System.identityHashCode(unsafeTx())).isEqualTo(outerHash));
            assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        } finally {
            ut.commit();
        }
    }

    // ============ REQUIRES_NEW ============

    @Test
    void requiresNewSuspendsAndResumes() throws Exception {
        ut.begin();
        int outerHash = System.identityHashCode(tm.getTransaction());
        svc.requiresNew(() -> {
            int innerHash = System.identityHashCode(unsafeTx());
            assertThat(innerHash).isNotEqualTo(outerHash);
        });
        assertThat(System.identityHashCode(tm.getTransaction())).isEqualTo(outerHash);
        ut.commit();
    }

    // ============ MANDATORY ============

    @Test
    void mandatoryFailsWithoutActiveTx() {
        assertThatThrownBy(() -> svc.mandatory(() -> {}))
                .isInstanceOf(TransactionalException.class);
    }

    @Test
    void mandatorySucceedsWithActiveTx() throws Exception {
        ut.begin();
        try {
            svc.mandatory(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE));
        } finally {
            ut.commit();
        }
    }

    // ============ NEVER ============

    @Test
    void neverFailsWithActiveTx() throws Exception {
        ut.begin();
        try {
            assertThatThrownBy(() -> svc.never(() -> {}))
                    .isInstanceOf(TransactionalException.class);
        } finally {
            ut.commit();
        }
    }

    @Test
    void neverSucceedsWithoutActiveTx() throws Exception {
        svc.never(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION));
    }

    // ============ NOT_SUPPORTED ============

    @Test
    void notSupportedSuspendsActiveTx() throws Exception {
        ut.begin();
        svc.notSupported(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION));
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        ut.commit();
    }

    // ============ SUPPORTS ============

    @Test
    void supportsRunsWithActiveTx() throws Exception {
        ut.begin();
        try {
            svc.supports(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE));
        } finally {
            ut.commit();
        }
    }

    @Test
    void supportsRunsWithoutActiveTx() throws Exception {
        svc.supports(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION));
    }

    // ============ Rollback rules ============

    @Test
    void runtimeExceptionRollsBackByDefault() {
        assertThatThrownBy(() -> svc.required(() -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
        assertThat(unsafeStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void checkedExceptionDoesNotRollBackByDefault() {
        assertThatThrownBy(() -> svc.required(() -> { throw new java.io.IOException("io"); }))
                .isInstanceOf(java.io.IOException.class);
        assertThat(unsafeStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void rollbackOnTriggersForCheckedException() {
        assertThatThrownBy(() -> svc.requiredRollbackOnIO(
                () -> { throw new java.io.IOException("io"); }))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void dontRollbackOnSuppressesRuntimeRollback() {
        assertThatThrownBy(() -> svc.requiredDontRollbackOnIllegalState(
                () -> { throw new IllegalStateException("ok"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(unsafeStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    private jakarta.transaction.Transaction unsafeTx() {
        try { return tm.getTransaction(); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private int unsafeStatus() {
        try { return tm.getStatus(); } catch (Exception e) { throw new RuntimeException(e); }
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
