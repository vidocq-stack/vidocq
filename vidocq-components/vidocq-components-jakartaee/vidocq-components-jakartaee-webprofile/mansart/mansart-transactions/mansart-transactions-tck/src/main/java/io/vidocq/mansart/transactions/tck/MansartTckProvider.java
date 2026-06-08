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
package io.vidocq.mansart.transactions.tck;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.UserTransaction;

/**
 * Adapter exposed to the official Jakarta Transactions 2.0 TCK harness.
 *
 * <p>The TCK loads this class via the system property
 * {@code jakarta.transaction.TransactionManager.implementation}
 * (configured in pom.xml — {@code tck-run} profile) and asks it for the four singletons every
 * Jakarta Transactions implementation must publish :
 * {@link TransactionManager}, {@link UserTransaction}, {@link TransactionSynchronizationRegistry},
 * and access to the underlying {@code Transaction} instance.
 *
 * <p>Singleton-by-design : the TCK assumes one TM per JVM. Our {@link MansartTransactionManager}
 * is intrinsically thread-safe (per-thread state via {@link ThreadLocal}), so a single instance
 * shared across the whole TCK run is correct.
 */
public final class MansartTckProvider {

    private static final MansartTransactionManager TM = new MansartTransactionManager();

    private MansartTckProvider() {}

    public static TransactionManager getTransactionManager() {
        return TM;
    }

    /**
     * The TCK occasionally asks for a {@link UserTransaction} facade — same underlying TM,
     * narrower API.
     */
    public static UserTransaction getUserTransaction() {
        return new MansartUserTransaction(TM);
    }
}
