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
 * Module-path proof vehicle for Heisenberg CDI: verifies that a {@code @Retry} bean is intercepted on
 * the strict module path with NO {@code opens} directive — including the {@code FaultToleranceInterceptor}
 * itself, which is instantiated in-module through the generated provider (VAU-INT-004) and whose
 * {@code @Inject} fields are package-private (assigned by an in-package {@code putfield}).
 *
 * <p>The Vauban APT generates {@code FaultToleranceService$$Intercepted} (build time) plus the in-module
 * {@code _VaubanComponents} provider declared below; the Vauban container instantiates, field-injects and
 * runs the interception chain through that provider, so this module opens nothing to
 * {@code io.vidocq.vauban.core}. It depends on {@code vauban-core} for real (it boots a container).</p>
 */
module io.vidocq.heisenberg.cdi.jpmsit {
    requires io.vidocq.heisenberg.cdi.vauban;
    requires io.vidocq.vauban.core;
    requires microprofile.fault.tolerance.api;

    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.heisenberg.cdi.jpmsit;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.heisenberg.cdi.jpmsit._VaubanComponents;
}
