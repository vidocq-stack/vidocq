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
package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.TransactionBridge;
import jakarta.enterprise.inject.Instance;

/**
 * Optional-dependency gate for the JTA transaction bridge (MANSART-007).
 *
 * <p>{@code jakarta.transaction} and {@code mansart-transactions-jdbc} are {@code requires
 * static} for this module: a deployment without a transaction manager (e.g. the Jakarta Data
 * TCK on Weld) must keep working. Only this class is safe to call from the always-loaded
 * wiring code — it probes both APIs by name and defers every typed reference to
 * {@link JtaBridgeFactory}, which is loaded only when the probe succeeds.
 */
final class JtaBridgeActivator {

    private static final boolean JTA_PRESENT = probe();

    private JtaBridgeActivator() {}

    private static boolean probe() {
        try {
            Class.forName("jakarta.transaction.TransactionManager");
            Class.forName("io.vidocq.mansart.transactions.jdbc.ConnectionXAResource");
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * The JTA bridge for this deployment, or {@code null} when the transaction APIs are absent
     * or no {@code TransactionManager}/{@code TransactionSynchronizationRegistry} beans are
     * resolvable (no transactions extension installed) — repositories then keep their
     * per-operation autocommit behaviour.
     */
    static TransactionBridge tryCreate(Instance<Object> lookup) {
        if (!JTA_PRESENT) return null;
        return JtaBridgeFactory.create(lookup);
    }
}
