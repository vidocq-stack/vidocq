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
package io.vidocq.runtime.tooling;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Catalogue of recognised Vidocq configuration namespaces, used to flag likely
 * typos in {@code vidocq.properties}.
 *
 * <p>Vidocq config keys live under the {@code vidocq.} prefix and many use a
 * dynamic third segment (e.g. {@code vidocq.http.mount.<name>.path},
 * {@code vidocq.pool.<datasource>.url}), so exact-key matching is impractical.
 * Instead a key is validated by its <em>namespace</em> — the first segment after
 * {@code vidocq.}. A key whose namespace is unknown (e.g. {@code vidocq.htpp.port})
 * is almost certainly a mistake.
 *
 * <p>Keys outside the {@code vidocq.} prefix are an application's own and are
 * always treated as known, so the validator never warns about them.
 */
public final class ConfigKeys {

    /** Prefix that scopes a key to the Vidocq runtime. */
    public static final String NAMESPACE_PREFIX = "vidocq.";

    /**
     * First-segment namespaces recognised under {@code vidocq.}. Derived from the
     * keys consumed across the runtime, Maven plugin and bundled extensions.
     */
    static final Set<String> KNOWN_NAMESPACES = Set.of(
            "app", "appName", "appDescription", "appVersion", "banner",
            "chappe", "checkpom", "compress", "config", "console", "data", "dev",
            "distDir", "distName", "docker", "http", "icon",
            "includeResources", "installerDir", "jlink", "jpackageType",
            "jvmArgs", "launch", "launcher", "log", "mainClass", "mainModule", "pool",
            "profile", "rest", "runtimeImage", "scriptName", "startup", "stripDebug",
            "vendor");

    private ConfigKeys() {}

    /**
     * Whether {@code key} is recognised. Non-{@code vidocq.} keys are always
     * considered known (an application may define its own); a {@code vidocq.}
     * key is known when its namespace is in {@link #KNOWN_NAMESPACES}.
     */
    public static boolean isKnown(String key) {
        if (key == null || !key.startsWith(NAMESPACE_PREFIX)) {
            return true;
        }
        String rest = key.substring(NAMESPACE_PREFIX.length());
        if (rest.isEmpty()) {
            return false;
        }
        int dot = rest.indexOf('.');
        String namespace = dot < 0 ? rest : rest.substring(0, dot);
        return KNOWN_NAMESPACES.contains(namespace);
    }

    /**
     * The key {@code key} most likely meant, for an unrecognised {@code vidocq.} key: its namespace replaced by the
     * closest known one, within two edits, a swap of two letters counting as one ({@code vidocq.htpp.port} →
     * {@code vidocq.http.port}). Empty for a recognised key, or when no namespace is that close (Vidocq/vidocq#353).
     */
    public static Optional<String> suggestion(String key) {
        if (isKnown(key)) {
            return Optional.empty();
        }
        String rest = key.substring(NAMESPACE_PREFIX.length());
        int dot = rest.indexOf('.');
        String namespace = dot < 0 ? rest : rest.substring(0, dot);
        String tail = dot < 0 ? "" : rest.substring(dot);
        return KNOWN_NAMESPACES.stream()
                .filter(known -> distance(namespace, known) <= 2)
                .min(Comparator.<String>comparingInt(known -> distance(namespace, known))
                        .thenComparing(Comparator.naturalOrder()))
                .map(known -> NAMESPACE_PREFIX + known + tail);
    }

    /** The edits from {@code a} to {@code b}: insertions, deletions, substitutions and swaps of two letters. */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /**
     * The {@code vidocq.}-namespaced keys in {@code keys} that are not recognised,
     * de-duplicated and sorted for stable reporting.
     */
    public static List<String> unknownKeys(Collection<String> keys) {
        return keys.stream()
                .filter(k -> !isKnown(k))
                .distinct()
                .sorted()
                .toList();
    }
}
