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
package io.vidocq.runtime.core.config;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the configured {@code vidocq.*} keys that no loaded extension consumes.
 *
 * <p>A key nobody reads is applied by nobody: the application silently keeps the default value,
 * and the mistake is invisible whenever the configured value happens to <em>be</em> the default.
 * That is how a documented {@code vidocq.http.port} could sit inert in applications for weeks
 * (Vidocq/chappe#7) — the whole class of typos and renamed keys has the same shape.
 *
 * <p>Auditing is <strong>opt-in per namespace</strong>: a key is only reported when some loaded
 * extension claims its {@code vidocq.<namespace>.} namespace through
 * {@link io.vidocq.runtime.spi.VidocqExtension#configKeys()}. An extension that declares nothing
 * therefore costs nothing and can never provoke a false warning — the cost of a wrong warning here
 * is higher than the cost of a missed one, since it would train users to ignore the log.
 */
public final class ConfigKeyAudit {

    /** Prefix that scopes a key to the Vidocq runtime. */
    private static final String NAMESPACE_PREFIX = "vidocq.";

    /**
     * Build-time keys published by {@code vidocq-runtime-maven-plugin} and the CLI. They travel as
     * system properties into the running JVM (notably under {@code vidocq:dev}) but are not runtime
     * configuration, so they are never reported.
     */
    private static final Set<String> BUILD_TIME_KEYS = Set.of(
            "vidocq.mainClass", "vidocq.mainModule", "vidocq.jvmArgs", "vidocq.scriptName",
            "vidocq.distName", "vidocq.distDir", "vidocq.launcher", "vidocq.runtimeImage",
            "vidocq.jlink", "vidocq.jpackageType", "vidocq.installerDir", "vidocq.icon",
            "vidocq.vendor", "vidocq.compress", "vidocq.stripDebug", "vidocq.includeResources",
            "vidocq.appName", "vidocq.appDescription", "vidocq.appVersion", "vidocq.docker",
            "vidocq.checkpom", "vidocq.profile");

    /** Build-time namespaces, matched by prefix. */
    private static final String BUILD_TIME_DEV_PREFIX = "vidocq.dev.";

    private ConfigKeyAudit() {}

    /**
     * The configured keys that fall under a claimed namespace yet match no declared key.
     *
     * @param propertyNames the configured property names, as reported by the config sources
     * @param declaredKeys  the union of every loaded extension's {@code configKeys()}; an entry
     *                      ending in {@code *} is a prefix
     * @return the unconsumed keys, sorted and deduplicated; empty when there is nothing to report
     */
    public static List<String> unconsumedKeys(Iterable<String> propertyNames, Set<String> declaredKeys) {
        Set<String> claimedNamespaces = claimedNamespaces(declaredKeys);
        if (claimedNamespaces.isEmpty()) {
            return List.of();
        }

        Set<String> unconsumed = new TreeSet<>();
        for (String key : propertyNames) {
            if (key == null || !key.startsWith(NAMESPACE_PREFIX)) {
                continue;
            }
            if (BUILD_TIME_KEYS.contains(key) || key.startsWith(BUILD_TIME_DEV_PREFIX)) {
                continue;
            }
            if (!claimedNamespaces.contains(namespaceOf(key))) {
                continue;
            }
            if (!isDeclared(key, declaredKeys)) {
                unconsumed.add(key);
            }
        }
        return List.copyOf(unconsumed);
    }

    /**
     * Formats the startup warning for one unconsumed key: it names the offending key and the
     * candidates it could have meant, so the reader is not left to grep the reference table.
     */
    public static String warningFor(String key, Set<String> declaredKeys) {
        String namespace = namespaceOf(key);
        String candidates = declaredKeys.stream()
                .filter(d -> namespaceOf(d).equals(namespace))
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
        return "Configuration key '" + key + "' is read by no extension and has no effect. "
                + "Known keys in this namespace: " + candidates;
    }

    /** The {@code vidocq.<namespace>.} scope of a key, or the key itself when it has no namespace. */
    private static String namespaceOf(String key) {
        int dot = key.indexOf('.', NAMESPACE_PREFIX.length());
        return dot < 0 ? key : key.substring(0, dot + 1);
    }

    /** The namespaces some extension claims, derived from the declared keys themselves. */
    private static Set<String> claimedNamespaces(Set<String> declaredKeys) {
        Set<String> namespaces = new TreeSet<>();
        for (String declared : declaredKeys) {
            if (declared != null && declared.startsWith(NAMESPACE_PREFIX)) {
                namespaces.add(namespaceOf(declared));
            }
        }
        return namespaces;
    }

    /** Whether a key matches a declared key exactly, or a declared {@code prefix*} entry. */
    private static boolean isDeclared(String key, Set<String> declaredKeys) {
        for (String declared : declaredKeys) {
            if (declared == null) {
                continue;
            }
            if (declared.endsWith("*")) {
                if (key.startsWith(declared.substring(0, declared.length() - 1))) {
                    return true;
                }
            } else if (declared.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
