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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#809: an extension id names the function first and the codename last ({@code health-knock}), and the
 * id each extension had up to 0.4.0 ({@code knock-health}) still resolves to the same artifact.
 */
class ExtensionIdsTest {

    /** The id of each catalog extension, and the one it had up to 0.4.0 (the same when it did not change). */
    private static final Map<String, String> FORMER_IDS = table(
            "config-ravel", "ravel-config",
            "crac", "crac",
            "data-mansart", "mansart-data",
            "el-peano", "peano-el",
            "fault-tolerance-heisenberg", "heisenberg-fault-tolerance",
            "health-knock", "knock-health",
            "jdbc-telemetry", "jdbc-telemetry",
            "jstl-bodoni", "bodoni-jstl",
            "jwt-cervantes", "cervantes-jwt",
            "metrics-dirac", "dirac-metrics",
            "migration", "migration",
            "migration-flyway", "flyway-migration",
            "migration-liquibase", "liquibase-migration",
            "naming", "naming",
            "openapi-grimm", "grimm-openapi",
            "openapi-ui-grimm", "grimm-openapi-ui",
            "pages-ibarra", "ibarra-pages",
            "persistence-noether", "noether-persistence",
            "pool-mansart", "mansart-pool",
            "rest-cassini", "cassini-rest",
            "rest-client-cyrano", "cyrano-rest-client",
            "security-kerckhoffs", "kerckhoffs-security",
            "servlet-foy", "foy-servlet",
            "telemetry-humboldt", "humboldt-telemetry",
            "transactions-mansart", "mansart-transactions",
            "webserver-chappe", "chappe-webserver",
            "websocket-foy", "foy-websocket");

    private static Map<String, String> table(String... idAndFormer) {
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = 0; i < idAndFormer.length; i += 2) {
            table.put(idAndFormer[i], idAndFormer[i + 1]);
        }
        return table;
    }

    @Test
    void theCatalogListsTheFunctionFirstIdsOnly() {
        assertEquals(List.copyOf(FORMER_IDS.keySet()), KnownExtensions.catalog().stream().map(RegistryEntry::id).toList());
    }

    @Test
    void theFormerIdOfEachExtensionResolvesToItsArtifact() {
        FORMER_IDS.forEach((id, former) -> {
            assertEquals(KnownExtensions.resolve(id), KnownExtensions.resolve(former), former);
            assertEquals(id, KnownExtensions.byId(former).orElseThrow().id(), former);
            assertEquals(id, KnownExtensions.canonicalId(former), former);
            assertTrue(KnownExtensions.isKnown(former), former);
        });
        assertEquals("vidocq-runtime-knock-health-extension", KnownExtensions.resolve("health-knock").artifactId());
        assertEquals("vidocq-runtime-cassini-rest-extension", KnownExtensions.resolve("REST-cassini").artifactId());
    }

    @Test
    void anExtensionKnowsTheIdsItHadBefore() {
        assertEquals(List.of("knock-health"), KnownExtensions.formerIds("health-knock"));
        assertEquals(List.of("knock-health"), KnownExtensions.formerIds("knock-health"));
        assertEquals(List.of(), KnownExtensions.formerIds("crac"));
        assertEquals(List.of(), KnownExtensions.formerIds("com.acme:acme-extension"));
    }

    /** A former id names one extension only, and never another extension's id. */
    @Test
    void noFormerIdIsTakenTwice() {
        List<String> ids = KnownExtensions.catalog().stream().map(RegistryEntry::id).toList();
        List<String> formers = ids.stream().flatMap(id -> KnownExtensions.formerIds(id).stream()).toList();
        assertEquals(formers.size(), Set.copyOf(formers).size(), formers.toString());
        formers.forEach(former -> assertFalse(ids.contains(former), former));
    }

    @Test
    void anArtifactIsNamedByItsNewId() {
        assertEquals("health-knock", KnownExtensions.idOf(
                new ExtensionCoordinate("io.vidocq.runtime.extensions.microprofile", "vidocq-runtime-knock-health-extension")));
    }

    @Test
    void whatTheDescriptorsSayOfOtherExtensionsUsesTheNewIds() {
        assertEquals(List.of("pool-mansart"), KnownExtensions.requiredExtensions("mansart-data"));
        assertEquals(List.of("pool-mansart", "transactions-mansart"),
                KnownExtensions.requiredExtensions("persistence-noether"));
        assertEquals(List.of("servlet-foy"), KnownExtensions.requiredExtensions("security-kerckhoffs"));
        assertEquals(List.of("telemetry-humboldt", "pool-mansart"), KnownExtensions.companionOf("jdbc-telemetry"));
    }

    /** A former id given with a new one is the same extension, not two. */
    @Test
    void requiredExtensionsAreCountedOnceWhateverIdNamesThem() {
        assertEquals(List.of("pool-mansart", "data-mansart"),
                KnownExtensions.withRequired(KnownExtensions.canonicalIds(List.of("mansart-pool", "data-mansart"))));
    }

    @Test
    void theFunctionAloneNamesTheOnlyExtensionThatStartsWithIt() {
        assertEquals("health-knock", KnownExtensions.canonicalId("health"));
        assertEquals("data-mansart", KnownExtensions.canonicalId("Data"));
        assertEquals(KnownExtensions.resolve("health-knock"), KnownExtensions.resolve("health"));
        // An id of its own wins over the ids it starts.
        assertEquals("migration", KnownExtensions.canonicalId("migration"));
        // Outside the catalog, the id is left as given.
        assertEquals("zzz", KnownExtensions.canonicalId(" zzz "));
        assertEquals(Optional.empty(), KnownExtensions.byId("health"));
    }

    @Test
    void aFunctionSeveralExtensionsStartWithNamesThemAll() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KnownExtensions.resolve("rest"));

        assertEquals("not in the Vidocq catalog. Did you mean: rest-cassini, rest-client-cyrano?"
                + " An extension outside the catalog is added as groupId:artifactId.", e.getMessage());
        assertEquals("rest", KnownExtensions.canonicalId("rest"));
        assertFalse(KnownExtensions.isKnown("rest"));
    }

    @Test
    void anUnknownIdNamesTheNewIdsOfTheExtensionsItIsCloseTo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> KnownExtensions.resolve("knock-helth"));

        assertTrue(e.getMessage().contains("Did you mean: health-knock?"), e.getMessage());
    }
}
