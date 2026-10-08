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
package io.vidocq.runtime.cli.ext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in, offline catalog of first-party Vidocq extensions, and the resolver
 * that turns a user-supplied id into a Maven {@link ExtensionCoordinate}.
 *
 * <p>This catalog is the offline fallback for {@code extension list --available}
 * when the remote registry is unreachable, and the lookup table that lets
 * {@code extension add <short-id>} inject the correct (category-specific) groupId.</p>
 */
public final class KnownExtensions {

    private KnownExtensions() {}

    private static final List<RegistryEntry> CATALOG = List.of(
            new RegistryEntry("chappe-webserver",
                    "io.vidocq.runtime.extensions.essentials",
                    "vidocq-runtime-chappe-webserver-extension",
                    "Chappe HTTP web server"),
            new RegistryEntry("ravel-config",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-ravel-config-extension",
                    "MicroProfile Config"),
            new RegistryEntry("cassini-rest",
                    "io.vidocq.runtime.extensions.jakartaee.core",
                    "vidocq-runtime-cassini-rest-extension",
                    "Jakarta REST (JAX-RS) endpoints"),
            new RegistryEntry("grimm-openapi",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-grimm-openapi-extension",
                    "MicroProfile OpenAPI document (/openapi)"),
            new RegistryEntry("grimm-openapi-ui",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-grimm-openapi-ui-extension",
                    "Swagger UI for the OpenAPI document"),
            new RegistryEntry("cyrano-rest-client",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-cyrano-rest-client-extension",
                    "MicroProfile REST Client"),
            new RegistryEntry("cervantes-jwt",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-cervantes-jwt-extension",
                    "MicroProfile JWT authentication"),
            new RegistryEntry("humboldt-telemetry",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-humboldt-telemetry-extension",
                    "MicroProfile Telemetry (tracing)"),
            new RegistryEntry("knock-health",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-knock-health-extension",
                    "MicroProfile Health checks"),
            new RegistryEntry("dirac-metrics",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-dirac-metrics-extension",
                    "MicroProfile Metrics"),
            new RegistryEntry("heisenberg-fault-tolerance",
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-heisenberg-fault-tolerance-extension",
                    "MicroProfile Fault Tolerance"),
            new RegistryEntry("mansart-data",
                    "io.vidocq.runtime.extensions.jakartaee.web",
                    "vidocq-runtime-mansart-data-extension",
                    "Jakarta Persistence (JPA)"),
            new RegistryEntry("mansart-pool",
                    "io.vidocq.runtime.extensions.jakartaee.web",
                    "vidocq-runtime-mansart-pool-extension",
                    "JDBC connection pooling"),
            new RegistryEntry("migration",
                    "io.vidocq.runtime.extensions.essentials",
                    "vidocq-runtime-migration-extension",
                    "Schema migrations applied at boot"),
            new RegistryEntry("flyway-migration",
                    "io.vidocq.runtime.extensions.essentials",
                    "vidocq-runtime-flyway-migration-extension",
                    "Flyway schema migrations"),
            new RegistryEntry("liquibase-migration",
                    "io.vidocq.runtime.extensions.essentials",
                    "vidocq-runtime-liquibase-migration-extension",
                    "Liquibase schema migrations"),
            new RegistryEntry("mansart-transactions",
                    "io.vidocq.runtime.extensions.jakartaee.web",
                    "vidocq-runtime-mansart-transactions-extension",
                    "Jakarta Transactions (JTA)"));

    /** The immutable built-in catalog. */
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

    /** Whether {@code id} maps to a known first-party extension. */
    public static boolean isKnown(String id) {
        return byId(id).isPresent();
    }

    /**
     * The APT codegen bundle that MUST sit on the compiler's
     * {@code annotationProcessorPaths} when the matching extension is on the
     * dependencies (enforced by {@code vidocq:checkpom}). Empty for extensions
     * that do not ship one.
     */
    public static Optional<ExtensionCoordinate> codegenBundle(String id) {
        return switch (id == null ? "" : id.trim().toLowerCase()) {
            case "cassini-rest" -> Optional.of(new ExtensionCoordinate(
                    "io.vidocq.runtime.extensions.jakartaee.core",
                    "vidocq-runtime-cassini-rest-extension-codegen"));
            case "mansart-data" -> Optional.of(new ExtensionCoordinate(
                    "io.vidocq.runtime.extensions.jakartaee.web",
                    "vidocq-runtime-mansart-data-extension-codegen"));
            case "mansart-transactions" -> Optional.of(new ExtensionCoordinate(
                    "io.vidocq.runtime.extensions.jakartaee.web",
                    "vidocq-runtime-mansart-transactions-extension-codegen"));
            case "ravel-config" -> Optional.of(new ExtensionCoordinate(
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-ravel-config-extension-codegen"));
            case "cyrano-rest-client" -> Optional.of(new ExtensionCoordinate(
                    "io.vidocq.runtime.extensions.microprofile",
                    "vidocq-runtime-cyrano-rest-client-extension-codegen"));
            default -> Optional.empty();
        };
    }

    /** The JPMS module each catalog extension ships, which an application {@code requires}. */
    private static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("chappe-webserver", "io.vidocq.runtime.extensions.essentials.chappe"),
            Map.entry("ravel-config", "io.vidocq.runtime.extensions.microprofile.ravel"),
            Map.entry("cassini-rest", "io.vidocq.runtime.extensions.jakartaee.core.cassini"),
            Map.entry("grimm-openapi", "io.vidocq.runtime.extensions.microprofile.grimm.openapi"),
            Map.entry("grimm-openapi-ui", "io.vidocq.runtime.extensions.microprofile.grimm.openapi.ui"),
            Map.entry("cyrano-rest-client", "io.vidocq.runtime.extensions.microprofile.cyrano"),
            Map.entry("cervantes-jwt", "io.vidocq.runtime.extensions.microprofile.cervantes"),
            Map.entry("humboldt-telemetry", "io.vidocq.runtime.extensions.microprofile.humboldt"),
            Map.entry("knock-health", "io.vidocq.runtime.extensions.microprofile.knock"),
            Map.entry("dirac-metrics", "io.vidocq.runtime.extensions.microprofile.dirac"),
            Map.entry("heisenberg-fault-tolerance", "io.vidocq.runtime.extensions.microprofile.heisenberg"),
            Map.entry("mansart-data", "io.vidocq.runtime.extensions.jakartaee.web.mansart.data"),
            Map.entry("mansart-pool", "io.vidocq.runtime.extensions.jakartaee.web.mansart.pool"),
            Map.entry("migration", "io.vidocq.runtime.extensions.essentials.migration"),
            Map.entry("flyway-migration", "io.vidocq.runtime.extensions.essentials.migration.flyway"),
            Map.entry("liquibase-migration", "io.vidocq.runtime.extensions.essentials.migration.liquibase"),
            Map.entry("mansart-transactions", "io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions"));

    /** The module a catalog extension ships; empty for an id outside the catalog. */
    public static Optional<String> moduleName(String id) {
        return Optional.ofNullable(id == null ? null : MODULES.get(id.trim().toLowerCase()));
    }

    /**
     * The {@code module-info.java} directives (without the trailing {@code ;}) an application
     * needs to use {@code id}: its module, which re-exports most extension APIs transitively,
     * plus what the extension leaves to the application — Cassini does not re-export
     * Jakarta REST, and its and Mansart Data's generated sources import {@code @Generated}.
     * {@code opens} is added only for an existing {@code applicationPackage}, which JAX-RS,
     * JSON-B and Jakarta Persistence reflect on. Empty for an id outside the catalog.
     */
    public static List<String> moduleDirectives(String id, String applicationPackage) {
        Optional<String> module = moduleName(id);
        if (module.isEmpty()) {
            return List.of();
        }
        List<String> directives = new ArrayList<>();
        boolean opens = false;
        switch (id.trim().toLowerCase()) {
            case "cassini-rest" -> {
                directives.addAll(List.of("requires static java.compiler", "requires jakarta.cdi",
                        "requires jakarta.inject", "requires jakarta.ws.rs", "requires jakarta.json.bind",
                        "requires " + module.get(),
                        "requires io.vidocq.cassini.api"));
                opens = true;
            }
            case "mansart-data" -> {
                directives.addAll(List.of("requires static java.compiler", "requires " + module.get()));
                opens = true;
            }
            default -> directives.add("requires " + module.get());
        }
        if (opens && applicationPackage != null && !applicationPackage.isBlank()) {
            directives.add("opens " + applicationPackage);
        }
        return List.copyOf(directives);
    }
}
