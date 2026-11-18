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

import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

/**
 * Aborts the transaction a request began and left active. Jakarta EE 11 Platform §4.2.2: returning from the
 * {@code service} or {@code doFilter} method to the network client with an active transaction context is an error,
 * and the web container detects it and aborts the transaction.
 *
 * <p>Foy and Cassini serve each request inside a CDI request context, and end that context on the thread that served
 * the request, so the transaction still associated with that thread when the context ends is the one the request left.
 * A transaction already active when the context started belongs to the code that started it and is left alone.
 * Without this, the transaction stayed associated with a thread that no longer runs, and kept its connection and the
 * locks it held (BUG-20261118-80).</p>
 */
@RequestScoped
public class RequestTransactions {
    private static final System.Logger LOG = System.getLogger(RequestTransactions.class.getName());

    private final TransactionManager manager;
    /** The transaction associated with the thread when the request context started, or {@code null}. */
    private Transaction found;

    /** For the client proxy the container generates, which delegates every call. */
    RequestTransactions() {
        this.manager = null;
    }

    @Inject
    RequestTransactions(TransactionManager manager) {
        this.manager = manager;
    }

    void started(@Observes @Initialized(RequestScoped.class) Object event) throws SystemException {
        found = manager.getTransaction();
    }

    void ending(@Observes @BeforeDestroyed(RequestScoped.class) Object event) {
        try {
            Transaction left = manager.getTransaction();
            if (left == null || left.equals(found)) return;
            manager.rollback();
            LOG.log(System.Logger.Level.WARNING, "A request ended with the transaction it began still active: rolled "
                    + "back (Jakarta EE Platform §4.2.2). Commit or roll back a transaction before the response.");
        } catch (SystemException | IllegalStateException failure) {
            LOG.log(System.Logger.Level.ERROR, "The transaction a request left active could not be rolled back",
                    failure);
        }
    }
}
