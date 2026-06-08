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
 * Module-path proof vehicle for Mansart Transactions CDI: verifies that a {@code @Transactional}
 * bean is intercepted on the module path with NO {@code opens} directive and NO runtime-generated
 * subclass.
 *
 * <p>The Vauban APT generates {@code TxService$$Intercepted} (build time) plus the in-module
 * {@code _VaubanComponents} provider declared below. The Vauban container instantiates the bean,
 * field-injects it and runs the interception chain through that provider, so this module opens
 * nothing to {@code io.vidocq.vauban.core}. It depends on {@code vauban-core} for real (it boots a
 * container, and the generated subclass references {@code io.vidocq.vauban.core.interceptor.*}).</p>
 */
module io.vidocq.mansart.transactions.jpmsit {
    requires io.vidocq.mansart.transactions.cdi;
    requires io.vidocq.vauban.core;

    requires jakarta.transaction;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.mansart.transactions.jpmsit;

    // Build-time, in-module instantiation + field injection of the @Transactional fixture bean — so
    // the container needs no `opens … to io.vidocq.vauban.core`.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.mansart.transactions.jpmsit._VaubanComponents;
}
