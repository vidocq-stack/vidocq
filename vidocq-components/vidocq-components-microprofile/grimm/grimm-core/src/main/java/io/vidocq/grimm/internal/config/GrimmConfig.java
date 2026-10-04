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
package io.vidocq.grimm.internal.config;

import org.eclipse.microprofile.config.Config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Immutable snapshot of all MicroProfile OpenAPI 4.2 configuration keys (spec §4.1).
 *
 * <p>Built once from a {@link Config} or {@link Map} source at container startup and
 * exposed to downstream pipeline stages (scanner, merger, filter invoker, endpoint).</p>
 *
 * <h2>Recognised keys</h2>
 * <ul>
 *   <li>{@code mp.openapi.scan.disable} — boolean</li>
 *   <li>{@code mp.openapi.scan.packages} — comma-separated list</li>
 *   <li>{@code mp.openapi.scan.classes} — comma-separated list</li>
 *   <li>{@code mp.openapi.scan.exclude.packages} — comma-separated list</li>
 *   <li>{@code mp.openapi.scan.exclude.classes} — comma-separated list</li>
 *   <li>{@code mp.openapi.filter} — FQCN of an {@code OASFilter}</li>
 *   <li>{@code mp.openapi.model.reader} — FQCN of an {@code OASModelReader}</li>
 *   <li>{@code mp.openapi.servers} — comma-separated list of server URLs to set on the model</li>
 *   <li>{@code mp.openapi.schema.<FQCN>} — JSON snippet defining a schema for a class</li>
 *   <li>{@code mp.openapi.extensions.scan.disable} — boolean; disables {@code @Extension} scanning</li>
 * </ul>
 *
 * <p>The keys {@code mp.openapi.servers.<name>.*} (per-server overrides) and
 * {@code mp.openapi.extensions.<key>=<value>} (document-level extensions) are intentionally
 * left out of M8: they are scheduled in M10+.</p>
 */
public record GrimmConfig(
        ScanConfig scan,
        FilterConfig filter,
        List<String> servers,
        Map<String, List<String>> pathServers,
        Map<String, List<String>> operationServers,
        Map<String, String> schemaOverrides,
        boolean extensionsScanDisable) {

    /** Compact-canonical constructor with null-tolerant defensive copies. */
    public GrimmConfig {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(filter, "filter");
        servers = List.copyOf(servers == null ? List.of() : servers);
        pathServers = immutableListMap(pathServers);
        operationServers = immutableListMap(operationServers);
        schemaOverrides = Map.copyOf(schemaOverrides == null ? Map.of() : schemaOverrides);
    }

    /** Empty configuration (scanning enabled, no filter / reader / overrides). */
    public static GrimmConfig defaults() {
        return new GrimmConfig(
                ScanConfig.defaultConfig(),
                FilterConfig.defaultConfig(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                false);
    }

    // ----------------- Factories -----------------

    /** Builds a snapshot from a flat key/value map (spec §4.1). */
    public static GrimmConfig fromMap(Map<String, String> source) {
        Function<String, String> getter = source::get;
        return fromGetter(getter, source.keySet());
    }

    /** Builds a snapshot from a live MicroProfile {@link Config}. */
    public static GrimmConfig fromMpConfig(Config config) {
        Objects.requireNonNull(config, "config");
        Function<String, String> getter = key ->
                config.getOptionalValue(key, String.class).orElse(null);
        Set<String> keys = new LinkedHashSet<>();
        config.getPropertyNames().forEach(keys::add);
        return fromGetter(getter, keys);
    }

    private static GrimmConfig fromGetter(Function<String, String> get, Set<String> knownKeys) {
        boolean disableScan = bool(get.apply("mp.openapi.scan.disable"));
        Set<String> incPkg = csvSet(get.apply("mp.openapi.scan.packages"));
        Set<String> incCls = csvSet(get.apply("mp.openapi.scan.classes"));
        Set<String> excPkg = csvSet(get.apply("mp.openapi.scan.exclude.packages"));
        Set<String> excCls = csvSet(get.apply("mp.openapi.scan.exclude.classes"));
        String bvRaw = get.apply("mp.openapi.scan.beanvalidation");
        boolean scanBeanValidation = bvRaw == null || !bvRaw.trim().equalsIgnoreCase("false");
        ScanConfig scan = new ScanConfig(disableScan, incPkg, incCls, excPkg, excCls, scanBeanValidation);

        FilterConfig filter = new FilterConfig(
                trimToNull(get.apply("mp.openapi.filter")),
                trimToNull(get.apply("mp.openapi.model.reader")));

        List<String> servers = List.copyOf(csvList(get.apply("mp.openapi.servers")));
        Map<String, List<String>> pathServers = new LinkedHashMap<>();
        Map<String, List<String>> operationServers = new LinkedHashMap<>();

        Map<String, String> schemaOverrides = new LinkedHashMap<>();
        String prefix = "mp.openapi.schema.";
        for (String key : knownKeys) {
            if (key != null && key.startsWith("mp.openapi.servers.path.")) {
                String pathKey = key.substring("mp.openapi.servers.path.".length());
                List<String> values = csvList(get.apply(key));
                if (!pathKey.isBlank() && !values.isEmpty()) {
                    pathServers.put(pathKey, values);
                }
                continue;
            }
            if (key != null && key.startsWith("mp.openapi.servers.operation.")) {
                String operationId = key.substring("mp.openapi.servers.operation.".length());
                List<String> values = csvList(get.apply(key));
                if (!operationId.isBlank() && !values.isEmpty()) {
                    operationServers.put(operationId, values);
                }
                continue;
            }
            if (key != null && key.startsWith(prefix) && key.length() > prefix.length()) {
                String fqcn = key.substring(prefix.length());
                String value = get.apply(key);
                if (value != null && !value.isBlank()) {
                    schemaOverrides.put(fqcn, value);
                }
            }
        }

        boolean extScanDisable = bool(get.apply("mp.openapi.extensions.scan.disable"));

        return new GrimmConfig(scan, filter, servers, pathServers, operationServers, schemaOverrides, extScanDisable);
    }

    private static Map<String, List<String>> immutableListMap(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            copy.put(entry.getKey(), List.copyOf(entry.getValue() == null ? List.of() : entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    // ----------------- Helpers -----------------

    private static boolean bool(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static Set<String> csvSet(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return Set.copyOf(out);
    }

    private static List<String> csvList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }
}

