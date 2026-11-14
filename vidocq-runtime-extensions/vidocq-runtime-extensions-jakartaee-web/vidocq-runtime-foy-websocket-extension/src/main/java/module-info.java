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
 * Jakarta WebSocket 2.2 through Foy: the {@code foy-websocket} module is part of the application, and the
 * {@code vidocq.websocket.allowed-origins} key reaches it as its servlet context init parameter. The application
 * module requires {@code jakarta.websocket}.
 */
module io.vidocq.runtime.extensions.jakartaee.web.websocket {
    requires io.vidocq.runtime.spi;
    requires io.vidocq.runtime.extensions.jakartaee.web.foy;
    // Foy's initializer registers the endpoints; the application module reads the API through its own requires.
    requires io.vidocq.foy.websocket;
    // Parses the allowed origins like the native WebSocket upgrade does.
    requires io.vidocq.chappe.api;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.websocket.FoyWebSocketExtension;
    provides io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletInitParameters
            with io.vidocq.runtime.extensions.jakartaee.web.websocket.AllowedOriginsParameter;
}
