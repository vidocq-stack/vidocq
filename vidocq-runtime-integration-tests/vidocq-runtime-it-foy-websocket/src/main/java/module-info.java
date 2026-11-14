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
module io.vidocq.runtime.it.foywebsocket {
    requires jakarta.cdi;
    requires jakarta.servlet;
    requires jakarta.websocket;
    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.web.foy;
    requires io.vidocq.runtime.extensions.jakartaee.web.websocket;
    requires io.vidocq.chappe.api;
    // Read by the generated factories below.
    requires io.vidocq.foy.api;
    requires io.vidocq.vauban.api;

    // Foy instantiates the programmatic endpoint (EchoEndpoint extends Endpoint) by reflection, the documented
    // fallback for a class no processor generates a factory for: the package is open to Foy's core, and to it alone.
    opens io.vidocq.runtime.it.foywebsocket to io.vidocq.foy.core;

    // No exports. The test boots the runtime without the application layer that
    // `Vidocq.run` creates, so the module declares the generated providers itself.
    provides io.vidocq.foy.spi.gen.WebComponent
            with io.vidocq.runtime.it.foywebsocket.EndpointRegistrar$$FoyComponent;
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.it.foywebsocket._VaubanComponents;
}
