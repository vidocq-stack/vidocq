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
import io.vidocq.runtime.extensions.jakartaee.web.foy.FoyExtension;

module io.vidocq.runtime.extensions.jakartaee.web.foy {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.runtime.extensions.essentials.chappe;
    requires io.vidocq.foy.chappe;
    requires io.vidocq.chappe.api;
    requires jakarta.servlet;
    requires jakarta.cdi;

    // The runtime reaches the extension through the services below only; the dev panel, which only
    // vidocq:dev adds, reads the live mounts.
    exports io.vidocq.runtime.extensions.jakartaee.web.foy.live
            to io.vidocq.runtime.extensions.jakartaee.web.foy.dev;

    provides io.vidocq.runtime.spi.VidocqExtension
            with FoyExtension;

    provides io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider
            with io.vidocq.runtime.extensions.jakartaee.web.foy.FoyMountHandlerProvider;
}
