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
 * Kerckhoffs — Jakarta Security 4.0 implementation.
 *
 * <p>The implementations live in internal packages, exported only to the modules that instantiate them
 * (milestone K1, see PLAN.md). The Authentication, Servlet and CDI APIs are {@code provided} at
 * the Maven level: the container supplies them, and Kerckhoffs never depends on an
 * implementation of them.
 */
module io.vidocq.kerckhoffs.core {
    // Module jakarta.security (artifact jakarta.security.enterprise-api): it requires
    // transitive jakarta.security.auth.message, jakarta.servlet and jakarta.json, and
    // requires jakarta.cdi and jakarta.el.
    requires transitive jakarta.security;
    // The *Expression attributes: the API only, the engine is found by ExpressionFactory.newInstance().
    requires jakarta.el;

    // The implementations the CDI integration instantiates; nothing else reaches them.
    exports io.vidocq.kerckhoffs.core.internal.identitystore to io.vidocq.kerckhoffs.cdi;
    // The module that places a mechanism in service, and the context it acts through: used by the Jakarta
    // Authentication profile that drives them.
    exports io.vidocq.kerckhoffs.core.internal.mechanism to io.vidocq.kerckhoffs.authentication, io.vidocq.kerckhoffs.cdi,
            io.vidocq.kerckhoffs.foy;
    // The evaluation of the *Expression attributes, asked by the CDI integration (interceptors and creators).
    exports io.vidocq.kerckhoffs.core.internal.expression to io.vidocq.kerckhoffs.cdi, io.vidocq.kerckhoffs.foy;
    // The security context, instantiated by the extension of the web container that knows how to find the request.
    exports io.vidocq.kerckhoffs.core.internal.context to io.vidocq.kerckhoffs.foy;
}
