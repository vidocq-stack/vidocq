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

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.transaction.TransactionManager;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The transactions of the application's {@link TransactionManager} bean, resolved on first use and kept for the boot.
 * The only class of the module that names a {@code jakarta.transaction} type: without the API it fails to load, which
 * {@link TransactionRunner#of} turns into "no transaction manager".
 */
final class JtaDemarcation implements TransactionRunner.Demarcation {

    private final Supplier<TransactionManager> resolve;
    private volatile TransactionManager manager;

    JtaDemarcation(Supplier<TransactionManager> resolve) {
        this.resolve = Objects.requireNonNull(resolve, "resolve");
    }

    /** The transactions of the {@link TransactionManager} bean of {@code beans}; {@code null} when there is none. */
    static TransactionRunner.Demarcation of(BeanManager beans) {
        if (beans == null || beans.getBeans(TransactionManager.class).isEmpty()) {
            return null;
        }
        return new JtaDemarcation(() -> {
            Bean<?> bean = beans.resolve(beans.getBeans(TransactionManager.class));
            return (TransactionManager) beans.getReference(bean, TransactionManager.class,
                    beans.createCreationalContext(bean));
        });
    }

    private TransactionManager manager() {
        TransactionManager found = manager;
        if (found == null) {
            found = resolve.get();
            manager = found;
        }
        return found;
    }

    @Override
    public void begin() throws Exception {
        manager().begin();
    }

    @Override
    public void commit() throws Exception {
        manager().commit();
    }

    @Override
    public void rollback() throws Exception {
        manager().rollback();
    }
}
