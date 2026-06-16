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
package io.vidocq.runtime.maven.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders the JDBC connection coordinates published by the dev services into a human-readable console
 * block and a machine-readable {@code vidocq-dev-services.properties} file, so any external tool — a SQL
 * client, a migration runner, a shell script — can reach the dev databases without guessing a random
 * port.
 *
 * <p>Provider-agnostic: it groups the collected {@code vidocq.pool[.<name>].url|username|password} keys
 * by datasource ({@code default} for the {@code @Default} pool, then each named one) and derives host and
 * port from the JDBC URL. Pure functions — the mojo owns the logging and the file IO.</p>
 */
final class DevServicesReport {

    private static final String POOL_PREFIX = "vidocq.pool.";

    /** First line of the console block — deliberately tool-neutral (not "DataGrip", not "psql"). */
    static final String CONSOLE_HEADER = "Connection information:";

    private DevServicesReport() {
    }

    /** A single datasource's reachable coordinates; {@code host}/{@code port} are {@code null} when not parseable. */
    record Coordinates(String name, String url, String host, String port, String username, String password) {
    }

    /**
     * Groups collected props by datasource. {@code default} (from {@code vidocq.pool.url|username|password})
     * comes first, then named datasources ({@code vidocq.pool.<name>.…}) in alphabetical order. A datasource
     * with no {@code url} is dropped — there is nothing to connect to.
     */
    static List<Coordinates> datasources(Map<String, String> collected) {
        Map<String, String> dflt = new LinkedHashMap<>();
        TreeMap<String, Map<String, String>> named = new TreeMap<>();
        for (Map.Entry<String, String> e : collected.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(POOL_PREFIX)) {
                continue;
            }
            String rest = key.substring(POOL_PREFIX.length());
            int dot = rest.lastIndexOf('.');
            if (dot < 0) {
                if (isField(rest)) {
                    dflt.put(rest, e.getValue());
                }
            } else {
                String name = rest.substring(0, dot);
                String field = rest.substring(dot + 1);
                if (name.indexOf('.') >= 0 || !isField(field)) {
                    continue; // nested name or a tuning key (maxSize, validation, …) — not a coordinate
                }
                named.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(field, e.getValue());
            }
        }
        List<Coordinates> out = new ArrayList<>();
        if (dflt.get("url") != null) {
            out.add(coordinatesOf("default", dflt));
        }
        for (Map.Entry<String, Map<String, String>> e : named.entrySet()) {
            if (e.getValue().get("url") != null) {
                out.add(coordinatesOf(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /** The console block lines (empty when no datasource was provisioned). The mojo logs each one. */
    static List<String> consoleLines(Map<String, String> collected) {
        List<Coordinates> ds = datasources(collected);
        if (ds.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add(CONSOLE_HEADER);
        for (Coordinates c : ds) {
            lines.add("  Datasource: " + c.name());
            lines.add("    JDBC URL : " + c.url());
            if (c.host() != null) {
                lines.add("    Host     : " + c.host());
            }
            if (c.port() != null) {
                lines.add("    Port     : " + c.port());
            }
            if (c.username() != null) {
                lines.add("    Username : " + c.username());
            }
            if (c.password() != null) {
                lines.add("    Password : " + c.password());
            }
        }
        return lines;
    }

    /** The {@code vidocq-dev-services.properties} body: {@code datasource.<name>.{url,host,port,username,password}}. */
    static String fileContent(Map<String, String> collected) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Vidocq dev services — connection information (generated; do not edit).\n");
        sb.append("# Rewritten on each `vidocq:dev` start.\n");
        for (Coordinates c : datasources(collected)) {
            String p = "datasource." + c.name() + ".";
            append(sb, p, "url", c.url());
            append(sb, p, "host", c.host());
            append(sb, p, "port", c.port());
            append(sb, p, "username", c.username());
            append(sb, p, "password", c.password());
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String prefix, String field, String value) {
        if (value != null) {
            sb.append(prefix).append(field).append('=').append(value).append('\n');
        }
    }

    private static Coordinates coordinatesOf(String name, Map<String, String> fields) {
        String url = fields.get("url");
        String host = null;
        String port = null;
        String authority = authorityOf(url);
        if (authority != null) {
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && colon < authority.length() - 1 && allDigits(authority.substring(colon + 1))) {
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            } else {
                host = authority;
            }
        }
        return new Coordinates(name, url, host, port, fields.get("username"), fields.get("password"));
    }

    /** Extracts the authority ({@code host[:port]}) from a {@code jdbc:xxx://authority/db?…} URL. */
    private static String authorityOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        int slashes = jdbcUrl.indexOf("//");
        if (slashes < 0) {
            return null;
        }
        int start = slashes + 2;
        int end = jdbcUrl.length();
        for (int i = start; i < jdbcUrl.length(); i++) {
            char ch = jdbcUrl.charAt(i);
            if (ch == '/' || ch == '?') {
                end = i;
                break;
            }
        }
        String authority = jdbcUrl.substring(start, end);
        return authority.isEmpty() ? null : authority;
    }

    private static boolean isField(String s) {
        return s.equals("url") || s.equals("username") || s.equals("password");
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
