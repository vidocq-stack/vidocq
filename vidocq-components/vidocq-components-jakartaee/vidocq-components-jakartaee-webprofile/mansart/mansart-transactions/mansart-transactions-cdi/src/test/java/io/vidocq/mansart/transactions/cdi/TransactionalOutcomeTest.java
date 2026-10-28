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

import io.vidocq.mansart.transactions.cdi.TransactionalInterceptorTest.TxService;
import io.vidocq.vauban.junit.AddBeans;
import io.vidocq.vauban.junit.VaubanTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How {@link TransactionalInterceptor} ends the transactions it starts or joins: rollback-only marking, the
 * outcome observed by a synchronization, and a {@code @Transactional} declared on the class.
 */
@VaubanTest
@AddBeans({
        MansartTransactionsProducer.class,
        TransactionalInterceptor.class,
        TransactionalInterceptorRequiresNew.class,
        TransactionalInterceptorNotSupported.class,
        TxService.class,
        TransactionalOutcomeTest.ClassLevelService.class
})
class TransactionalOutcomeTest {

    @Inject TransactionManager tm;
    @Inject TxService svc;
    @Inject ClassLevelService classLevel;

    private final List<Integer> outcomes = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) tm.rollback();
    }

    /** Records the completion status of the current transaction. */
    private void observe() throws Exception {
        tm.getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // only the outcome matters
            }

            @Override
            public void afterCompletion(int status) {
                outcomes.add(status);
            }
        });
    }

    @Test
    void ownTransactionMarkedRollbackOnlyIsRolledBack() throws Exception {
        svc.required(() -> {
            observe();
            tm.setRollbackOnly();
        });
        assertThat(outcomes).containsExactly(Status.STATUS_ROLLEDBACK);
    }

    @Test
    void checkedExceptionAfterRollbackOnlyRollsBack() {
        assertThatThrownBy(() -> svc.required(() -> {
            observe();
            tm.setRollbackOnly();
            throw new IOException("io");
        })).isInstanceOf(IOException.class);
        assertThat(outcomes).containsExactly(Status.STATUS_ROLLEDBACK);
    }

    @Test
    void checkedExceptionCommitsTheOwnTransaction() {
        assertThatThrownBy(() -> svc.required(() -> {
            observe();
            throw new IOException("io");
        })).isInstanceOf(IOException.class);
        assertThat(outcomes).containsExactly(Status.STATUS_COMMITTED);
    }

    @Test
    void runtimeExceptionInAJoinedTransactionMarksItRollbackOnly() throws Exception {
        tm.begin();
        assertThatThrownBy(() -> svc.required(() -> {
            throw new IllegalArgumentException("bad");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        tm.rollback();
    }

    @Test
    void requiresNewAndNotSupportedWithoutAnActiveTransaction() throws Exception {
        svc.requiresNew(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE));
        svc.notSupported(() -> assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION));
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void transactionalOnTheClassAppliesToItsMethods() throws Exception {
        assertThat(classLevel.statusInside()).isEqualTo(Status.STATUS_ACTIVE);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @ApplicationScoped
    @Transactional
    public static class ClassLevelService {

        @Inject TransactionManager tm;

        public int statusInside() throws Exception {
            return tm.getStatus();
        }
    }
}
