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

import io.vidocq.runtime.core.config.ConfigKeyAudit;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void strictModeIsOptInAndReachesEveryTarget() {
        Map<String, String> pools = Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit");
        assertTrue(MigrationExtension.buildTargets(MapConfig.of(pools)).stream()
                .noneMatch(MigrationTarget::failOnMissingLocations), "off by default");
        var strict = new java.util.HashMap<>(pools);
        strict.put("vidocq.migration.failOnMissingLocations", "true");
        assertTrue(MigrationExtension.buildTargets(MapConfig.of(strict)).stream()
                .allMatch(MigrationTarget::failOnMissingLocations));
    }

    @Test
    void theDeclaredKeysAreExactSoATypoIsReadByNothing() {
        MigrationExtension ext = new MigrationExtension(() -> List.of(new FakeMigrator("flyway")));
        ext.configure(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit",
                "vidocq.migration.location", "classpath:db/typo")));
        assertEquals(Set.of("vidocq.migration.enabled", "vidocq.migration.engine", "vidocq.migration.locations",
                "vidocq.migration.failOnMissingLocations", "vidocq.migration.audit.locations"), ext.configKeys());
        assertEquals(List.of("vidocq.migration.location"), ConfigKeyAudit.unconsumedKeys(List.of(
                "vidocq.pool.url", "vidocq.migration.audit.locations", "vidocq.migration.location"),
                ext.configKeys()), "a prefix vidocq.migration.* would consume the typo too, and hide it");
    }

    // ── vidocq#96 task 4: startup report ────────────────────────────────────────

    @Test
    void aDatasourceThatFoundNothingIsReportedAtEveryLevel() {
        MigrationExtension ext = new MigrationExtension(() -> List.of(
                new FakeMigrator("flyway", new MigrationResult(0, "(none)", true), List.of("classpath:db/migration"))));
        ext.configure(MapConfig.of(Map.of("vidocq.pool.url", "jdbc:h2:mem:x")));
        ext.beforeStart(null);
        RecordedSection section = new RecordedSection();

        ext.contribute(new ReportContext(Verbosity.OFF), section);

        assertEquals("flyway: default not migrated", section.summary);
        assertEquals(List.of(new RecordedSection.Anomaly("VIDOCQ-MIG-001",
                "datasource default: no migration found in [classpath:db/migration] and none in its schema history;"
                        + " the schema was not migrated",
                "Check vidocq.migration.locations and that the scripts are in the application")), section.anomalies);
    }

    @Test
    void aNamedDatasourceThatFoundNothingNamesItsOwnKey() {
        MigrationExtension ext = new MigrationExtension(() -> List.of(
                new FakeMigrator("flyway", new MigrationResult(0, "(none)", true), List.of("classpath:db/migration"))));
        ext.configure(MapConfig.of(Map.of(
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit")));
        ext.beforeStart(null);
        RecordedSection section = new RecordedSection();

        ext.contribute(new ReportContext(Verbosity.SUMMARY), section);

        assertEquals(1, section.anomalies.size());
        assertEquals("datasource audit: no migration found in [classpath:db/audit] and none in its schema history;"
                + " the schema was not migrated", section.anomalies.get(0).message());
        assertEquals("Check vidocq.migration.audit.locations and that the scripts are in the application",
                section.anomalies.get(0).hint());
    }

    @Test
    void aMigratedDatasourceIsSummarisedThenDetailed() {
        MigrationExtension ext = new MigrationExtension(() -> List.of(
                new FakeMigrator("flyway", new MigrationResult(2, "2", false), List.of("classpath:db/migration"))));
        ext.configure(MapConfig.of(Map.of("vidocq.pool.url", "jdbc:h2:mem:x")));
        ext.beforeStart(null);
        RecordedSection section = new RecordedSection();

        ext.contribute(new ReportContext(Verbosity.DETAILED), section);

        assertEquals("flyway: default 2 applied, version 2", section.summary);
        assertEquals(List.of("default=[2 applied, version 2]", "default locations=[classpath:db/migration]"),
                section.rows);
        assertTrue(section.anomalies.isEmpty());
    }

    @Test
    void anIdleOrDisabledMigrationSaysSo() {
        MigrationExtension idle = new MigrationExtension(List::of);
        idle.configure(MapConfig.of(Map.of()));
        RecordedSection idleSection = new RecordedSection();
        idle.contribute(new ReportContext(Verbosity.DETAILED), idleSection);
        assertEquals("idle: no vidocq.pool[.<name>].url", idleSection.summary);

        MigrationExtension disabled = new MigrationExtension(List::of);
        disabled.configure(MapConfig.of(Map.of("vidocq.migration.enabled", "false", "vidocq.pool.url", "jdbc:h2:mem:x")));
        RecordedSection disabledSection = new RecordedSection();
        disabled.contribute(new ReportContext(Verbosity.DETAILED), disabledSection);
        assertEquals("disabled: vidocq.migration.enabled=false", disabledSection.summary);
    }

    @Test
    void aTwoValueResultNeverSaysNothingWasFound() {
        assertFalse(new MigrationResult(0, "(none)").nothingFound());
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

    private record FakeMigrator(String engine, MigrationResult result, List<String> defaultLocations)
            implements SchemaMigrator {
        FakeMigrator(String engine) {
            this(engine, new MigrationResult(0, "fake"), List.of());
        }

        @Override public MigrationResult migrate(MigrationTarget target) {
            return result;
        }
    }

    /** What a contributor writes: its summary, its rows and lists as {@code key=[values]}, its anomalies. */
    private static final class RecordedSection implements StartupReportSection {
        record Anomaly(String code, String message, String hint) {}

        String summary;
        final List<String> rows = new ArrayList<>();
        final List<Anomaly> anomalies = new ArrayList<>();

        @Override public StartupReportSection summary(String text) { summary = text; return this; }
        @Override public StartupReportSection row(String key, Object value) {
            rows.add(key + "=[" + value + "]");
            return this;
        }
        @Override public StartupReportSection list(String key, Collection<String> items) {
            rows.add(key + "=" + items);
            return this;
        }
        @Override public StartupReportSection secret(String key, boolean configured) {
            throw new UnsupportedOperationException();
        }
        @Override public StartupReportSection listener(String name, String boundBaseUri) {
            throw new UnsupportedOperationException();
        }
        @Override public StartupReportSection route(String listener, String method, String path, String handler) {
            throw new UnsupportedOperationException();
        }
        @Override public StartupReportSection anomaly(String code, String message, String hint) {
            anomalies.add(new Anomaly(code, message, hint));
            return this;
        }
    }

    private record ReportContext(Verbosity verbosity) implements StartupReportContext {
        @Override public LaunchMode launchMode() { return LaunchMode.DEV; }
        @Override public boolean hasBeanOfType(String typeName) { return false; }
        @Override public <T> Optional<T> lookup(Class<T> type) { return Optional.empty(); }
        @Override public List<String> routeUrls(String handlerClassName) { return List.of(); }
    }
}
