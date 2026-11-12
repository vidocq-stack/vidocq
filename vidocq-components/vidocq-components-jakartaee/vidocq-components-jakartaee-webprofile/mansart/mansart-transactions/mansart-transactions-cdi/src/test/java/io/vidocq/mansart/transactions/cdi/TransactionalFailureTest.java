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
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the caller of a {@code @Transactional} method sees when the method fails: its own exception, through
 * any depth of joined calls, and a thread that no transaction is left bound to — after an {@link Error} too.
 */
@VaubanTest
@AddBeans({
        MansartTransactionsProducer.class,
        TransactionalInterceptor.class,
        TransactionalFailureTest.Outer.class,
        TransactionalFailureTest.Middle.class,
        TransactionalFailureTest.Inner.class
})
class TransactionalFailureTest {

    @Inject TransactionManager tm;
    @Inject Outer outer;
    @Inject Inner inner;

    @AfterEach
    void cleanup() {
        try {
            if (tm != null && tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
                tm.rollback();
            }
        } catch (Exception _) { /* best effort */ }
    }

    @Test
    void anExceptionCrossingTwoJoinedCallsReachesTheCallerUnchanged() {
        assertThatThrownBy(() -> outer.callMiddle(new IllegalArgumentException("business")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("business");
    }

    @Test
    void anErrorLeavesNoTransactionBoundToTheThread() throws Exception {
        assertThatThrownBy(() -> inner.fail(new AssertionError("boom"))).isInstanceOf(AssertionError.class);

        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @ApplicationScoped
    public static class Outer {
        @Inject Middle middle;

        @Transactional
        public void callMiddle(Throwable failure) throws Throwable {
            middle.callInner(failure);
        }
    }

    @ApplicationScoped
    public static class Middle {
        @Inject Inner inner;

        @Transactional
        public void callInner(Throwable failure) throws Throwable {
            inner.fail(failure);
        }
    }

    @ApplicationScoped
    public static class Inner {
        @Transactional
        public void fail(Throwable failure) throws Throwable {
            throw failure;
        }
    }
}
