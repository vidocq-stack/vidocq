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
package io.vidocq.runtime.cli.config;

import java.util.Collection;
import java.util.List;
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
            "app", "appName", "appDescription", "appVersion",
            "chappe", "checkpom", "compress", "config", "console", "data", "dev",
            "distDir", "distName", "docker", "http", "icon",
            "includeResources", "installerDir", "jlink", "jpackageType",
            "jvmArgs", "launcher", "log", "mainClass", "mainModule", "pool",
            "profile", "rest", "runtimeImage", "scriptName", "stripDebug",
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
