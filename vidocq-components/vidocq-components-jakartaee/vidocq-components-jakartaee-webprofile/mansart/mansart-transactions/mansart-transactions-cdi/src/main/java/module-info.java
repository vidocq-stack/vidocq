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
/**
 * CDI 4.1 bootstrap for Mansart Transactions — exposes the {@link jakarta.transaction.TransactionManager}
 * /{@link jakarta.transaction.UserTransaction}/{@link jakarta.transaction.TransactionSynchronizationRegistry}
 * as CDI beans, declares the {@link jakarta.transaction.Transactional} interceptor (REQUIRED,
 * REQUIRES_NEW, MANDATORY, NEVER, NOT_SUPPORTED, SUPPORTS) and the {@link jakarta.transaction.TransactionScoped}
 * scope.
 *
 * <p>Wired via the CDI 4.1 BuildCompatibleExtension API
 * ({@link io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension}).
 * The {@link jakarta.transaction.TransactionScoped} scope is registered using
 * {@code MetaAnnotations.addContext(scope, isNormal, contextClass)} which Vauban (and any
 * CDI 4.1-compliant container) honours natively.
 */
module io.vidocq.mansart.transactions.cdi {
    requires transitive io.vidocq.mansart.transactions.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    exports io.vidocq.mansart.transactions.cdi;

    // No `opens`: the producer, the @TransactionScoped context AND the six @Transactional interceptors
    // are all instantiated and field-injected in-module by the APT-generated _VaubanComponents provider
    // below. The interceptors' public @AroundInvoke methods are reachable without opens (exported
    // package, public member). Proven on the strict module path by mansart-transactions-cdi-module-it.

    // In-module instantiation, field injection and producer invocation of this package's beans (the
    // six @Transactional interceptors, the TM/UT/TSR producer and the @TransactionScoped context),
    // generated as _VaubanComponents — APT-generated, inert under Weld.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.mansart.transactions.cdi._VaubanComponents;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
}
