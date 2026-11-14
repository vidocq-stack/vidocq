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
package io.vidocq.runtime.extensions.jakartaee.web.websocket;

import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletInitParameters;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.Map;

/**
 * Gives Foy the allowed origins of {@value FoyWebSocketExtension#ORIGINS_KEY}. The key's syntax is checked at
 * start by the Chappe web server extension, which also applies it to the native WebSocket upgrade.
 */
public final class AllowedOriginsParameter implements ServletInitParameters {

    /** The init parameter of {@code foy-websocket}, with the syntax of the key. */
    static final String FOY_PARAMETER = "io.vidocq.foy.websocket.allowed-origins";

    @Override
    public Map<String, String> initParameters(VidocqConfig config) {
        return config.getValue(FoyWebSocketExtension.ORIGINS_KEY, String.class)
                .map(origins -> Map.of(FOY_PARAMETER, origins))
                .orElse(Map.of());
    }
}
