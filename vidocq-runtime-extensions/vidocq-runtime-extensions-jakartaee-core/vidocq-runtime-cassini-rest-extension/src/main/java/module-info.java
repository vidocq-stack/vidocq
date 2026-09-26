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
import io.vidocq.runtime.extensions.jakartaee.core.cassini.CassiniExtension;
import io.vidocq.runtime.extensions.jakartaee.core.cassini.CassiniMountHandlerProvider;

module io.vidocq.runtime.extensions.jakartaee.core.cassini {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.runtime.extensions.essentials.chappe;
    requires io.vidocq.vauban.core;

    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.cassini.cdi.vauban;
    // Maintenance API only (CassiniMaintenance.resetDiscoveryCaches on hot reload)
    requires io.vidocq.cassini.core;

    requires io.vidocq.chappe.api;
    requires jakarta.cdi;
    requires jakarta.annotation;

    exports io.vidocq.runtime.extensions.jakartaee.core.cassini;

    // What the rest panel of the -dev module reads (Vidocq/vidocq#143); no other module sees it.
    exports io.vidocq.runtime.extensions.jakartaee.core.cassini.live
            to io.vidocq.runtime.extensions.jakartaee.core.cassini.dev;

    provides io.vidocq.runtime.spi.VidocqExtension
            with CassiniExtension;

    provides io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider
            with CassiniMountHandlerProvider;
}
