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
