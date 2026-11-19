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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.List;

/** A transaction manager that records {@code begin}, {@code commit} and {@code rollback}, and may fail a commit. */
final class RecordingTransactionManager implements TransactionManager {

    final List<String> events = new ArrayList<>();
    RollbackException commitFailure;

    @Override
    public void begin() {
        events.add("begin");
    }

    @Override
    public void commit() throws RollbackException {
        events.add("commit");
        if (commitFailure != null) {
            throw commitFailure;
        }
    }

    @Override
    public void rollback() {
        events.add("rollback");
    }

    @Override
    public int getStatus() {
        return Status.STATUS_NO_TRANSACTION;
    }

    @Override
    public Transaction getTransaction() {
        return null;
    }

    @Override
    public void resume(Transaction transaction) {
        // nothing suspended here
    }

    @Override
    public void setRollbackOnly() {
        events.add("rollback-only");
    }

    @Override
    public void setTransactionTimeout(int seconds) {
        // no timeout here
    }

    @Override
    public Transaction suspend() {
        return null;
    }
}
