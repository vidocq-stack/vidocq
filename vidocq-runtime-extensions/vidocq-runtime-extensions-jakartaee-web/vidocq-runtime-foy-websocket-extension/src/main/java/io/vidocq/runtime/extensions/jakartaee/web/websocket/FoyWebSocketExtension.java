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

import io.vidocq.chappe.api.WebSocketOrigins;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Jakarta WebSocket 2.2 through Foy. Foy's {@code ServletContainerInitializer} registers the endpoints of the
 * application; this extension puts the module in the application and reports the origins it accepts. The origins
 * come from {@value #ORIGINS_KEY}. {@link AllowedOriginsParameter} checks the key first, at start, with the parser
 * of the native WebSocket upgrade, and gives it to Foy; the Chappe web server extension then applies it to the native
 * routes. The startup report reads a snapshot taken at start.
 */
public final class FoyWebSocketExtension implements VidocqExtension, StartupReportContributor {

    /** The configuration key of the allowed origins. */
    static final String ORIGINS_KEY = "vidocq.websocket.allowed-origins";

    private static final String ORIGINS_ROW = "allowed origins";

    private final AtomicReference<String> startedWith = new AtomicReference<>();

    @Override public String name() { return "foy-websocket"; }
    @Override public String id() { return "websocket"; }
    @Override public String title() { return "Jakarta WebSocket (Foy)"; }

    @Override
    public Set<String> configKeys() {
        return Set.of(ORIGINS_KEY);
    }

    @Override
    public void onStart(ExtensionContext context) {
        startedWith.set(describe(context.config().getValue(ORIGINS_KEY, String.class).orElse("")));
    }

    @Override
    public void onStop() {
        startedWith.set(null);
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        section.summary("Jakarta WebSocket 2.2");
        String origins = startedWith.get();
        section.row(ORIGINS_ROW, origins == null ? "not initialized" : origins);
    }

    private static String describe(String configured) {
        String origins = configured.strip();
        if (origins.isEmpty()) {
            return "same origin only";
        }
        return WebSocketOrigins.parse(origins).allowsAny() ? "any" : origins;
    }
}
