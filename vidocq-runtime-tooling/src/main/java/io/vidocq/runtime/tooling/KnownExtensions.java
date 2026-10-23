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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Built-in, offline catalog of first-party Vidocq extensions, and the resolver
 * that turns a user-supplied id into a Maven {@link ExtensionCoordinate}.
 *
 * <p>This catalog is the offline fallback for {@code extension list --available}
 * when the remote registry is unreachable, and the lookup table that lets
 * {@code extension add <short-id>} inject the correct (category-specific) groupId.</p>
 *
 * <p>The extensions describe themselves: each ships {@value #DESCRIPTOR}, and the
 * {@value #CATALOG_RESOURCE} this class reads is built from those descriptors and
 * checked against them by {@code ExtensionCatalogTest} (vidocq#314).</p>
 */
public final class KnownExtensions {

    private KnownExtensions() {}

    /** The descriptor each first-party extension ships: its short id, description, codegen bundle, directives. */
    static final String DESCRIPTOR = "META-INF/vidocq/extension.properties";

    /** The catalog built from those descriptors (see {@code ExtensionCatalogTest}). */
    static final String CATALOG_RESOURCE = "META-INF/vidocq/extension-catalog.properties";

    private static final String ARTIFACT_ID = ".artifactId";

    /** Every catalog key, prefixed by the extension's short id, e.g. {@code knock-health.groupId}. */
    private static final Properties DESCRIPTORS = load();

    private static final List<RegistryEntry> CATALOG = DESCRIPTORS.stringPropertyNames().stream()
            .filter(key -> key.endsWith(ARTIFACT_ID))
            .map(key -> key.substring(0, key.length() - ARTIFACT_ID.length()))
            .sorted()
            .map(id -> new RegistryEntry(id, property(id, "groupId"), property(id, "artifactId"),
                    property(id, "description")))
            .toList();

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = KnownExtensions.class.getResourceAsStream("/" + CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CATALOG_RESOURCE + " is missing from the Vidocq CLI");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + CATALOG_RESOURCE, e);
        }
        return props;
    }

    private static String property(String id, String key) {
        return DESCRIPTORS.getProperty(id + "." + key, "").strip();
    }

    private static List<String> csv(String value) {
        List<String> out = new ArrayList<>();
        for (String token : value.split(",")) {
            if (!token.isBlank()) {
                out.add(token.strip());
            }
        }
        return out;
    }

    /** The immutable built-in catalog, sorted by id. */
    public static List<RegistryEntry> catalog() {
        return CATALOG;
    }

    /** Looks up a catalog entry by its short id (case-insensitive). */
    public static Optional<RegistryEntry> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String needle = id.trim();
        return CATALOG.stream()
                .filter(e -> e.id().equalsIgnoreCase(needle))
                .findFirst();
    }

    /**
     * Resolves a user-supplied id to a coordinate:
     * <ol>
     *   <li>a known catalog short id → its (category-specific) coordinate;</li>
     *   <li>an explicit {@code groupId:artifactId} → parsed verbatim;</li>
     *   <li>otherwise → the default convention
     *       {@code io.vidocq.runtime:vidocq-runtime-<id>-extension}.</li>
     * </ol>
     */
    public static ExtensionCoordinate resolve(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("extension id must not be blank");
        }
        String trimmed = id.trim();
        return byId(trimmed)
                .map(RegistryEntry::coordinate)
                .orElseGet(() -> trimmed.contains(":")
                        ? ExtensionCoordinate.parse(trimmed)
                        : new ExtensionCoordinate(
                                "io.vidocq.runtime",
                                "vidocq-runtime-" + trimmed + "-extension"));
    }

    /** The catalog id of the extension at {@code coordinate}, else its artifactId. */
    public static String idOf(ExtensionCoordinate coordinate) {
        return CATALOG.stream()
                .filter(e -> e.coordinate().equals(coordinate))
                .map(RegistryEntry::id)
                .findFirst()
                .orElse(coordinate.artifactId());
    }

    /** Whether {@code id} maps to a known first-party extension. */
    public static boolean isKnown(String id) {
        return byId(id).isPresent();
    }

    /**
     * The APT codegen bundle that MUST sit on the compiler's
     * {@code annotationProcessorPaths} when the matching extension is on the
     * dependencies (enforced by {@code vidocq:checkpom}). It shares the extension's
     * groupId. Empty for extensions that do not ship one.
     */
    public static Optional<ExtensionCoordinate> codegenBundle(String id) {
        return byId(id).flatMap(e -> Optional.of(property(e.id(), "codegen"))
                .filter(bundle -> !bundle.isEmpty())
                .map(bundle -> new ExtensionCoordinate(e.groupId(), bundle)));
    }

    /** The module a catalog extension ships, which an application {@code requires}; empty outside the catalog. */
    public static Optional<String> moduleName(String id) {
        return byId(id).map(e -> property(e.id(), "module")).filter(module -> !module.isEmpty());
    }

    /**
     * The {@code module-info.java} directives (without the trailing {@code ;}) an application
     * needs to use {@code id}: its module, which re-exports most extension APIs transitively,
     * plus what the extension's descriptor says it leaves to the application — Cassini does not
     * re-export Jakarta REST, and its and Mansart Data's generated sources import {@code @Generated}.
     * {@code opens} is added only for an existing {@code applicationPackage}, when the descriptor
     * asks for it (JAX-RS, JSON-B and Jakarta Persistence reflect on it). Empty for an id outside the catalog.
     */
    public static List<String> moduleDirectives(String id, String applicationPackage) {
        Optional<String> module = moduleName(id);
        if (module.isEmpty()) {
            return List.of();
        }
        String key = byId(id).orElseThrow().id();
        List<String> directives = new ArrayList<>();
        csv(property(key, "module-info.requires.static")).forEach(m -> directives.add("requires static " + m));
        directives.add("requires " + module.get());
        csv(property(key, "module-info.requires")).forEach(m -> directives.add("requires " + m));
        if (Boolean.parseBoolean(property(key, "module-info.opens-application-package"))
                && applicationPackage != null && !applicationPackage.isBlank()) {
            directives.add("opens " + applicationPackage);
        }
        return List.copyOf(directives);
    }
}
