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
 * An MCP server application on Vidocq whose only MCP dependency is the langchain4j-cdi MCP extension. Open, like any
 * application whose beans langchain4j-cdi calls reflectively.
 */
open module io.vidocq.runtime.it.lc4jcdimcp {
    requires io.vidocq.runtime.core;
    // The application compiles against the MCP server's annotations and types; mcp.server.api comes with it.
    requires dev.langchain4j.cdi.mcp.server;
    // for the tests only, which send the dev console an action request with an Origin of their choice
    requires static java.net.http;

    exports io.vidocq.runtime.it.lc4jcdimcp;
}
