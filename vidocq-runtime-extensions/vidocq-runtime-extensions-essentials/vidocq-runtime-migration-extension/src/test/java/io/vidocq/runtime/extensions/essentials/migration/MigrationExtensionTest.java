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
package io.vidocq.runtime.extensions.essentials.migration;

import io.vidocq.runtime.spi.VidocqConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationExtensionTest {

    @Test
    void defaultTargetIsBuiltFromPoolUrl() {
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:x", "vidocq.pool.username", "sa")));
        assertEquals(1, targets.size());
        assertEquals("default", targets.get(0).dataSourceName());
        assertEquals("jdbc:h2:mem:x", targets.get(0).jdbcUrl());
        assertEquals("sa", targets.get(0).username());
        assertTrue(targets.get(0).locations().isEmpty(), "empty → backend default");
    }

    @Test
    void namedDatasourceIsOptInViaLocations() {
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit")));
        assertEquals(2, targets.size());
        var audit = targets.stream().filter(t -> "audit".equals(t.dataSourceName())).findFirst().orElseThrow();
        assertEquals("jdbc:h2:mem:audit", audit.jdbcUrl());
        assertEquals(List.of("classpath:db/audit"), audit.locations());
    }

    @Test
    void noPoolUrlMeansNoTargets() {
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of()));
        assertTrue(targets.isEmpty());
    }

    @Test
    void selectReturnsUniqueBackend() {
        var fake = new FakeMigrator("flyway");
        var result = MigrationExtension.select(List.of(fake), Optional.empty());
        assertSame(fake, result);
    }

    @Test
    void selectThrowsOnEmpty() {
        assertThrows(IllegalStateException.class,
                () -> MigrationExtension.select(List.of(), Optional.empty()));
    }

    @Test
    void selectThrowsAmbiguousWhenNoEngine() {
        var fly = new FakeMigrator("flyway");
        var liq = new FakeMigrator("liquibase");
        assertThrows(IllegalStateException.class,
                () -> MigrationExtension.select(List.of(fly, liq), Optional.empty()));
    }

    @Test
    void selectDisambiguatesByEngineWhenSeveral() {
        var fly = new FakeMigrator("flyway");
        var liq = new FakeMigrator("liquibase");
        assertSame(liq, MigrationExtension.select(List.of(fly, liq), Optional.of("liquibase")));
    }

    @Test
    void namedDatasourceWithoutLocationsIsSkipped() {
        // a named datasource present in the pool but with NO vidocq.migration.<name>.locations is opt-out.
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit")));
        assertEquals(List.of("default"),
                targets.stream().map(MigrationTarget::dataSourceName).toList());
    }

    @Test
    void disabledShortCircuitsBeforeBackendSelection() {
        // enabled=false must skip everything — even though a pool.url is set and NO backend is on the
        // path, which would otherwise make configure() throw at select().
        MigrationExtension ext = new MigrationExtension();
        ext.configure(MapConfig.of(Map.of(
                "vidocq.migration.enabled", "false",
                "vidocq.pool.url", "jdbc:h2:mem:x")));
        assertDoesNotThrow(() -> ext.beforeStart(null));
    }

    // ── vidocq#96 ─────────────────────────────────────────────────────────────

    @Test
    void theDefaultLocationsKeyOverridesTheDefaultDatasourceAlone() {
        // vidocq.migration.locations starts with "vidocq.migration." and ends with ".locations", which share
        // its dot: the named-datasource scan used to call substring(17, 16) on it and fail the boot.
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:x",
                "vidocq.migration.locations", "filesystem:src/main/resources/db/migration")));
        assertEquals(List.of("default"), targets.stream().map(MigrationTarget::dataSourceName).toList());
        assertEquals(List.of("filesystem:src/main/resources/db/migration"), targets.get(0).locations());
    }

    @Test
    void theDefaultLocationsKeyNextToANamedOne() {
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.locations", "classpath:db/main",
                "vidocq.migration.audit.locations", "classpath:db/audit")));
        assertEquals(List.of("default", "audit"), targets.stream().map(MigrationTarget::dataSourceName).toList());
        assertEquals(List.of("classpath:db/main"), targets.get(0).locations());
        assertEquals(List.of("classpath:db/audit"), targets.get(1).locations());
    }

    // ── test doubles ─────────────────────────────────────────────────────────

    private record MapConfig(Map<String, String> data) implements VidocqConfiguration {
        static MapConfig of(Map<String, String> data) { return new MapConfig(data); }
        @Override public Optional<String> property(String key) {
            return Optional.ofNullable(data.get(key));
        }
        @Override public Iterable<String> propertyNames() {
            return data.keySet();
        }
    }

    private record FakeMigrator(String engine) implements SchemaMigrator {
        @Override public MigrationResult migrate(MigrationTarget target) {
            return new MigrationResult(0, "fake");
        }
    }
}
