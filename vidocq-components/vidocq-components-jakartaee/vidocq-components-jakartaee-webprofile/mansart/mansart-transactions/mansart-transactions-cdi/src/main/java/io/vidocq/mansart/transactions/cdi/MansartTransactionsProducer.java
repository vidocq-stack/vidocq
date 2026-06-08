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

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.UserTransaction;

/**
 * CDI producer publishing the three Jakarta Transactions singletons backed by a single
 * {@link MansartTransactionManager} instance per JVM.
 *
 * <p>Singleton-per-JVM is the right granularity: the TM holds per-thread state via
 * {@link ThreadLocal}, so sharing one TM across all consumers is correct AND required
 * (otherwise {@code @Transactional} on bean A and bean B see two independent thread-locals).
 *
 * <p>Discovered by {@link MansartTransactionsExtension} via
 * {@code ScannedClasses.add(...)} at {@code @Discovery}. Vauban-processor indexes the class
 * for validation but does not emit a {@code *_Factory.class} in the user module's output —
 * the class lives in this jar and Vauban-runtime falls back to a reflective factory. The
 * {@code @Produces} methods on this class are then scanned normally and the three transactions
 * beans become available for {@code @Inject}.
 */
@ApplicationScoped
public class MansartTransactionsProducer {

    private static final MansartTransactionManager TM = new MansartTransactionManager();

    /** Same instance the producer publishes — used by {@link TransactionScopedContext} which
     *  needs the TM <i>before</i> CDI injection is online to resolve the
     *  {@code @TransactionScoped} scope. */
    static MansartTransactionManager tm() {
        return TM;
    }

    @Produces
    @Singleton
    public TransactionManager transactionManager() {
        return TM;
    }

    @Produces
    @Singleton
    public UserTransaction userTransaction() {
        return new MansartUserTransactionFacade(TM);
    }

    @Produces
    @Singleton
    public TransactionSynchronizationRegistry transactionSynchronizationRegistry() {
        return new MansartTSR(TM);
    }
}
