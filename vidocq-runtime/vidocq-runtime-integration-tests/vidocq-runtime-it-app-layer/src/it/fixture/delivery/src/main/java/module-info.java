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
module acme.delivery {
    requires jakarta.cdi;
    requires jakarta.ws.rs;
    requires jakarta.annotation;
    requires jakarta.interceptor;
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
    requires io.vidocq.cassini.api;
    requires io.vidocq.runtime.extensions.microprofile.cervantes;
    requires org.eclipse.microprofile.config;

    // What the processors generate: Cassini's adapter and routes, Vauban's components. No package is open.
    provides io.vidocq.cassini.spi.gen.ResourceAdapter with acme.delivery.SecuredResource$$CassiniAdapter;
    provides io.vidocq.cassini.spi.gen.RouteProvider with acme.delivery.SecuredResource$$CassiniRoutes;
    provides io.vidocq.vauban.api.VaubanComponentProvider with acme.delivery._VaubanComponents;
}
