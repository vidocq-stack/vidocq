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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions;

import io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Jakarta EE 11 Platform §4.2.2: a web component that returns to the network client with an active transaction is in
 * error, and the web container aborts that transaction. Foy and Cassini serve a request inside a CDI request context,
 * so the transaction a request begins and leaves is rolled back when that context ends (BUG-20261118-80).
 */
class RequestTransactionsTest {
    private final MansartTransactionManager manager = new MansartTransactionManager();

    @AfterEach
    void noTransactionLeft() throws Exception {
        if (manager.getTransaction() != null) manager.rollback();
    }

    /** The status the transaction completed with, -1 while it has not. */
    private AtomicInteger completion() throws Exception {
        var status = new AtomicInteger(-1);
        manager.getTransaction().registerSynchronization(new Synchronization() {
            @Override public void beforeCompletion() {
                // nothing to flush
            }
            @Override public void afterCompletion(int completed) {
                status.set(completed);
            }
        });
        return status;
    }

    @Test
    void aTransactionTheRequestBeganAndLeftActiveIsRolledBackWhenTheRequestEnds() throws Exception {
        var request = new RequestTransactions(manager);
        request.started(new Object());
        manager.begin();
        var completion = completion();
        request.ending(new Object());
        assertEquals(Status.STATUS_ROLLEDBACK, completion.get());
        assertNull(manager.getTransaction());
    }

    @Test
    void aTransactionTheRequestMarkedForRollbackIsRolledBackToo() throws Exception {
        var request = new RequestTransactions(manager);
        request.started(new Object());
        manager.begin();
        manager.setRollbackOnly();
        request.ending(new Object());
        assertNull(manager.getTransaction());
        assertEquals(Status.STATUS_NO_TRANSACTION, manager.getStatus());
    }

    @Test
    void aTransactionActiveBeforeTheRequestStartedIsLeftToItsOwner() throws Exception {
        manager.begin();
        var outer = manager.getTransaction();
        var request = new RequestTransactions(manager);
        request.started(new Object());
        request.ending(new Object());
        assertSame(outer, manager.getTransaction());
        assertEquals(Status.STATUS_ACTIVE, manager.getStatus());
    }

    @Test
    void aRequestWithoutATransactionLeavesNothingBehind() throws Exception {
        var request = new RequestTransactions(manager);
        request.started(new Object());
        request.ending(new Object());
        assertEquals(Status.STATUS_NO_TRANSACTION, manager.getStatus());
    }

    /** A rollback that fails is reported, and the request context still ends. */
    @Test
    void aRollbackThatFailsDoesNotStopTheRequestFromEnding() throws Exception {
        var failing = new MansartTransactionManager() {
            @Override public void rollback() throws SystemException {
                throw new SystemException("the resource manager is gone");
            }
        };
        var request = new RequestTransactions(failing);
        request.started(new Object());
        failing.begin();
        assertDoesNotThrow(() -> request.ending(new Object()));
        failing.getTransaction().rollback();
        failing.suspend();
    }

    /** The container calls the observers when a request context ends, as Foy ends it after each request. */
    @Test
    void theContainerRollsBackWhatARequestLeftActiveWhenItsContextEnds() throws Exception {
        try (var container = new VaubanContainerBuilder().addComponentProvider(new _VaubanComponents())
                .addBeanClass(MansartTransactionsExtension.class).addBeanClass(RequestTransactions.class).build()) {
            TransactionManager transactions = container.select(TransactionManager.class);
            RequestContextController request = container.getBeanManager().createInstance()
                .select(RequestContextController.class).get();
            request.activate();
            transactions.begin();
            request.deactivate();
            assertNull(transactions.getTransaction());
        }
    }
}
