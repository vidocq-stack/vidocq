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
package io.vidocq.runtime.examples.mansart;

import jakarta.transaction.TransactionScoped;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-transaction operation audit bean. Demonstrates the {@code @TransactionScoped} CDI scope
 * provided by {@code mansart-transactions-cdi}: an instance is created on first access inside a
 * transaction, accumulates audit entries while the transaction is active, and is destroyed when
 * the transaction completes (commit or rollback).
 *
 * <p>Per Jakarta Transactions 2.0 §3.7, beans annotated with {@link TransactionScoped} must be
 * {@link Serializable} so that containers can passivate them between calls.
 */
@TransactionScoped
public class OperationAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<String> entries = new ArrayList<>();

    public void record(String op) {
        entries.add(op);
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }
}
