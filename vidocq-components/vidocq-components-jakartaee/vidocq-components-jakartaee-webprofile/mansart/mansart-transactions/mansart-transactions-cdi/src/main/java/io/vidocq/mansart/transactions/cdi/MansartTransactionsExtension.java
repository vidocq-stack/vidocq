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

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.transaction.TransactionScoped;

/**
 * CDI 4.1 BuildCompatibleExtension that wires the {@link TransactionScoped} scope into the bean
 * container and exposes the {@link MansartTransactionsProducer} (which publishes
 * {@link jakarta.transaction.TransactionManager} / {@link jakarta.transaction.UserTransaction} /
 * {@link jakarta.transaction.TransactionSynchronizationRegistry}) plus the six {@code @Transactional}
 * interceptor classes through {@code ScannedClasses.add(...)}.
 *
 * <p>All these classes ship in this jar — they are not in the user application's APT round
 * (Vauban-processor only scans the module being compiled). Without explicit registration the
 * runtime container would not see them, leaving {@code @Inject TransactionManager} unsatisfied
 * and the {@code @Transactional} interceptor silently inactive.
 *
 * <p>Vauban-processor honours {@code ScannedClasses.add(...)} by indexing the classes for the
 * deployment validator, but skips emitting {@code *_Factory.class} in the user module — those
 * factory classes would clash JPMS with the package exported by this jar (split-package). At
 * runtime Vauban falls back to a reflective factory for these classes, so no on-disk artefact
 * is needed.
 *
 * <p>The {@link TransactionScopedContext} is instantiated by the container via its public no-arg
 * constructor and is then queried for every {@code @TransactionScoped} bean lookup. The context
 * resolves the {@link jakarta.transaction.TransactionManager} lazily through the static accessor
 * on {@link MansartTransactionsProducer} — no injection chicken-and-egg problem.
 *
 * <p>Listed in {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}.
 */
public final class MansartTransactionsExtension implements BuildCompatibleExtension {

    @Discovery
    public void registerTransactionScopeAndScannedClasses(MetaAnnotations meta, ScannedClasses scanned) {
        meta.addContext(TransactionScoped.class, true /* normal scope */, TransactionScopedContext.class);

        // Producer for TM / UT / TSR — must be in the index so its @Produces methods are visible.
        scanned.add("io.vidocq.mansart.transactions.cdi.MansartTransactionsProducer");

        // The six @Transactional interceptor classes ship in this jar.
        scanned.add("io.vidocq.mansart.transactions.cdi.TransactionalInterceptor");
        scanned.add("io.vidocq.mansart.transactions.cdi.TransactionalInterceptorRequiresNew");
        scanned.add("io.vidocq.mansart.transactions.cdi.TransactionalInterceptorMandatory");
        scanned.add("io.vidocq.mansart.transactions.cdi.TransactionalInterceptorNever");
        scanned.add("io.vidocq.mansart.transactions.cdi.TransactionalInterceptorNotSupported");
        scanned.add("io.vidocq.mansart.transactions.cdi.TransactionalInterceptorSupports");
    }

    /**
     * Declares {@link TransactionScoped} as a trigger annotation so compile-time bean discovery
     * (Vauban's APT) includes user beans annotated {@code @TransactionScoped} in its bean index.
     * Without this, classes annotated only with {@code @TransactionScoped} would be invisible to
     * the build-time scan because the processor only watches a fixed list of standard CDI scopes.
     */
    @Enhancement(types = Object.class, withAnnotations = TransactionScoped.class, withSubtypes = true)
    public void registerTransactionScopedTrigger(ClassConfig clazz) {
        // No-op: the sole purpose is to advertise @TransactionScoped via the BCE
        // @Enhancement(withAnnotations=...) contract so the APT round picks up annotated classes.
    }
}
