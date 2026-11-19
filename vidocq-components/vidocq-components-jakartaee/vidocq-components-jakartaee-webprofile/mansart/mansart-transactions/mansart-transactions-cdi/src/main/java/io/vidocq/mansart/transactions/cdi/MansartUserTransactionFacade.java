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

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;

/**
 * Thin {@link UserTransaction} facade over a {@link TransactionManager} — same delegation
 * pattern as the TCK adapter, kept in this package to avoid leaking a runtime dep on the
 * tck module.
 */
final class MansartUserTransactionFacade implements UserTransaction {

    private final TransactionManager tm;

    MansartUserTransactionFacade(TransactionManager tm) {
        this.tm = tm;
    }

    @Override public void begin() throws NotSupportedException, SystemException { tm.begin(); }

    @Override public void commit()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
                   SecurityException, IllegalStateException, SystemException {
        tm.commit();
    }

    @Override public void rollback() throws IllegalStateException, SecurityException, SystemException {
        tm.rollback();
    }

    @Override public void setRollbackOnly() throws IllegalStateException, SystemException {
        tm.setRollbackOnly();
    }

    @Override public int getStatus() throws SystemException { return tm.getStatus(); }

    @Override public void setTransactionTimeout(int seconds) throws SystemException {
        tm.setTransactionTimeout(seconds);
    }
}
