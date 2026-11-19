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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The database a connection talks to, read from its JDBC URL: {@code db.system.name}, {@code db.namespace},
 * {@code server.address} and {@code server.port} of the OpenTelemetry database conventions.
 *
 * @param host {@code null} for an embedded database
 * @param port {@code -1} when the URL gives none
 */
record DbTarget(String system, String namespace, String host, int port) {

    /** The JDBC subprotocols whose convention name differs. */
    private static final Map<String, String> SYSTEMS = Map.of(
            "sqlserver", "microsoft.sql_server",
            "oracle", "oracle.db",
            "db2", "ibm.db2",
            "derby", "derby",
            "hsqldb", "hsqldb");

    private static final Pattern SUBPROTOCOL = Pattern.compile("^jdbc:([a-zA-Z0-9]+):(.*)$", Pattern.DOTALL);
    private static final Pattern AUTHORITY = Pattern.compile("//\\[?([^/:;?\\]]+)\\]?(?::(\\d+))?(/[^;?&]*)?");
    private static final Pattern H2_NAME = Pattern.compile("^(?:mem:|file:|tcp://[^/]+/|ssl://[^/]+/)?([^;]*)");

    static DbTarget of(String url) {
        if (url == null) {
            return new DbTarget("other_sql", null, null, -1);
        }
        Matcher sub = SUBPROTOCOL.matcher(url);
        if (!sub.matches()) {
            return new DbTarget("other_sql", null, null, -1);
        }
        String protocol = sub.group(1).toLowerCase(Locale.ROOT);
        String rest = sub.group(2);
        String system = SYSTEMS.getOrDefault(protocol, protocol);
        Matcher authority = AUTHORITY.matcher(rest);
        String host = null;
        int port = -1;
        String namespace = null;
        if (authority.find()) {
            host = authority.group(1);
            port = authority.group(2) == null ? -1 : Integer.parseInt(authority.group(2));
            String path = authority.group(3);
            if (path != null && path.length() > 1) {
                namespace = path.substring(1);
            }
        }
        if ("h2".equals(protocol)) {
            Matcher name = H2_NAME.matcher(rest);
            if (name.find() && !name.group(1).isEmpty()) {
                String file = name.group(1);
                namespace = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
            }
            if (!rest.startsWith("tcp:") && !rest.startsWith("ssl:")) {
                host = null;
                port = -1;
            }
        }
        return new DbTarget(system, namespace, host, port);
    }
}
