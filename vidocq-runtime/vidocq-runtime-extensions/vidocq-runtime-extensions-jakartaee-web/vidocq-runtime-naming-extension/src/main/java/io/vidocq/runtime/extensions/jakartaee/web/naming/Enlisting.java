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
package io.vidocq.runtime.extensions.jakartaee.web.naming;

import io.vidocq.mansart.transactions.jdbc.JtaDataSource;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import javax.sql.DataSource;
import java.util.function.UnaryOperator;

/**
 * A pool seen through the transactions of the application: Mansart's {@link JtaDataSource} over it, with the
 * container's transaction manager. The only class of this module that names Jakarta Transactions and Mansart
 * Transactions, both optional ({@code requires static}): {@link PoolNames} loads it only when the container has a
 * transaction manager, which Mansart Transactions provides.
 */
final class Enlisting {

    private Enlisting() {
    }

    /** Wraps a pool so that its connections take part in the current transaction. */
    static UnaryOperator<DataSource> over(BeanManager beanManager) {
        Instance<Object> beans = beanManager.createInstance();
        TransactionManager manager = beans.select(TransactionManager.class).get();
        TransactionSynchronizationRegistry registry = beans.select(TransactionSynchronizationRegistry.class).get();
        return pool -> new JtaDataSource(pool, manager, registry);
    }
}
