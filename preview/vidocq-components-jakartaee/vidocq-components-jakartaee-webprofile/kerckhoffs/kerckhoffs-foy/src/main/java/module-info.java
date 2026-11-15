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
 * Kerckhoffs — engine adapter for Foy: bridges the neutral request authentication hook of Foy to the Jakarta
 * Authentication profile and the HTTP authentication mechanism of Jakarta Security. Foy never depends on it.
 */
module io.vidocq.kerckhoffs.foy {
    requires io.vidocq.kerckhoffs.core;
    requires io.vidocq.kerckhoffs.authentication;
    requires io.vidocq.foy.api;
    requires jakarta.servlet;
    requires static jakarta.cdi;
    requires static jakarta.inject;

    // The container instantiates the creator of the security context.
    exports io.vidocq.kerckhoffs.foy;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.kerckhoffs.foy.FoySecurityContextExtension;
}
