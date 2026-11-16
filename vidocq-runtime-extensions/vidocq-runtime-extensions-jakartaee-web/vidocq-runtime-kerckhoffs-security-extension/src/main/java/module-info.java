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
 * Jakarta Security 4.0 on the Vidocq runtime, with Kerckhoffs: the application's {@code HttpAuthenticationMechanism}
 * authenticates every request Foy serves, and the {@code SecurityContext} answers for its caller.
 */
module io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs {
    requires transitive io.vidocq.runtime.spi;
    // The application's mechanisms and identity stores carry the Jakarta Security API: it reaches it through here.
    requires transitive jakarta.security;
    requires io.vidocq.vauban.core;
    requires io.vidocq.kerckhoffs.cdi;
    requires io.vidocq.kerckhoffs.foy;
    requires io.vidocq.runtime.extensions.jakartaee.web.foy;
    // The Expression Language engine of the *Expression attributes (@RememberMe): Peano, found by ServiceLoader.
    requires io.vidocq.runtime.extensions.jakartaee.core.peano;
    requires io.vidocq.foy.api;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    // The beans of this module are created in-module by the generated provider: no `opens` to the container.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs._VaubanComponents;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs.KerckhoffsSecurityExtension;

    provides io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletAuthentication
            with io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs.KerckhoffsServletAuthentication;
}
