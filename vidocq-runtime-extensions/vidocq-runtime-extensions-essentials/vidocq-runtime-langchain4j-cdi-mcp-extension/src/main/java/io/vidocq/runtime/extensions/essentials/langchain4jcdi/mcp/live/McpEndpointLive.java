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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live;

import java.util.List;

/**
 * The absolute URLs of {@code /mcp} the startup report resolved, for the MCP inspector of the {@code -dev} module
 * only: a live panel's {@code start} gets an {@code ExtensionContext}, which cannot resolve a route to a URL.
 * Published when the {@code mcp} section is written, which is before the console starts its live panels, and cleared
 * first when the extension stops, so that a dev reload never calls the previous boot's address.
 */
public final class McpEndpointLive {

    private static volatile List<String> urls = List.of();

    private McpEndpointLive() {}

    /** The URLs of this boot, each once, without the internal listen route; empty before the report is written. */
    public static List<String> urls() {
        return urls;
    }

    /** Publishes the URLs of this boot. */
    public static void publish(List<String> resolved) {
        urls = List.copyOf(resolved);
    }

    /** Forgets them. */
    public static void clear() {
        urls = List.of();
    }
}
