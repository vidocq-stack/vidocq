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
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
                "vidocq.migration.failOnMissingLocations", "vidocq.migration.cleanDisabled",
                "vidocq.migration.audit.locations", "vidocq.migration.audit.cleanDisabled"), ext.configKeys());
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

    // ── vidocq#120: migrate and clean-and-migrate from the dev console ─────────

    @Test
    void cleanIsDisabledUnlessItsKeyIsFalse() {
        Map<String, String> pools = new HashMap<>(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit"));
        assertTrue(MigrationExtension.buildTargets(MapConfig.of(pools)).stream()
                .allMatch(MigrationTarget::cleanDisabled), "disabled by default, as Flyway's own");
        pools.put("vidocq.migration.cleanDisabled", "yes");
        pools.put("vidocq.migration.audit.cleanDisabled", " False ");
        var targets = MigrationExtension.buildTargets(MapConfig.of(pools));
        assertTrue(targets.get(0).cleanDisabled(), "anything but false keeps it disabled");
        assertFalse(targets.get(1).cleanDisabled());
    }

    @Test
    void theActionsTakeAMigratedDatasourceByName() {
        MigrationExtension ext = booted(new SchemaFake(), Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit"));

        List<PanelAction> actions = ext.actions();

        assertEquals(List.of("migrate", "clean-and-migrate"), actions.stream().map(PanelAction::id).toList());
        assertEquals(List.of("Migrate now", "Clean and migrate"), actions.stream().map(PanelAction::label).toList());
        assertEquals(null, actions.get(0).confirmation());
        assertEquals("Drop every object in the schema of the chosen datasource, then migrate it again?"
                + " This cannot be undone.", actions.get(1).confirmation());
        for (PanelAction action : actions) {
            assertEquals(1, action.arguments().size());
            assertEquals("datasource", action.arguments().get(0).name());
            assertEquals(List.of("default", "audit"), action.arguments().get(0).allowedValues());
        }
    }

    @Test
    void nothingMigratedOffersNoAction() {
        MigrationExtension idle = new MigrationExtension(List::of);
        idle.configure(MapConfig.of(Map.of()));
        assertTrue(idle.actions().isEmpty());
        RecordingSample sample = new RecordingSample();
        idle.sample(sample);
        assertTrue(sample.isEmpty());
    }

    @Test
    void migrateAppliesWhatWasAddedAfterTheBootAndReplacesTheOutcome() {
        SchemaFake schema = new SchemaFake("1", "2");
        MigrationExtension ext = booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        schema.available.add("3");

        String result = run(ext, "migrate", "default");

        assertEquals("default: 1 migration applied, schema at version 3", result);
        RecordingSample group = sampled(ext).groups().get("default");
        assertEquals("3", group.text("version"));
        assertEquals("migrate: 1 applied", group.text("last-run"));
        assertEquals("[[1, V1, SQL, 2026-09-23T10:00:00Z, Success], [2, V2, SQL, 2026-09-23T10:00:00Z, Success],"
                + " [3, V3, SQL, 2026-09-23T10:00:00Z, Success]]", group.text("applied"));
        assertEquals("[]", group.text("pending"));

        MigrationResult kept = ext.outcomes().get(0).result();
        assertEquals(new MigrationResult(1, "3", false), kept, "the outcome the report reads is replaced too");
    }

    @Test
    void theBootListsTheMigrationsInADevLaunchOnly() {
        SchemaFake schema = new SchemaFake("1");
        MigrationExtension dev = booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        RecordingSample devGroup = sampled(dev).groups().get("default");
        assertEquals("table", devGroup.kind("applied"));
        assertEquals("boot: 1 applied", devGroup.text("last-run"));

        MigrationExtension prod = new MigrationExtension(() -> List.of(new SchemaFake("1")));
        prod.configure(MapConfig.of(Map.of("vidocq.pool.url", "jdbc:h2:mem:def")));
        prod.beforeStart(null);
        prod.onStart(new StartContext(LaunchMode.PROD));
        RecordingSample prodGroup = sampled(prod).groups().get("default");
        assertEquals("absent", prodGroup.kind("applied"));
        assertEquals("listed in a dev launch only", prodGroup.text("applied"));
        assertEquals("absent", prodGroup.kind("pending"));
    }

    @Test
    void theMigrationsAreListedByAnActionNeverBySample() {
        SchemaFake schema = new SchemaFake("1");
        MigrationExtension ext = booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        schema.available.add("2");
        assertEquals("[]", sampled(ext).groups().get("default").text("pending"), "sample reads no database");

        schema.applyNothing = true;
        assertEquals("default: 0 migrations applied, schema at version 1", run(ext, "migrate", "default"));

        RecordingSample group = sampled(ext).groups().get("default");
        assertEquals("[[2, V2, SQL, , Pending]]", group.text("pending"));
        assertEquals("[[1, V1, SQL, 2026-09-23T10:00:00Z, Success]]", group.text("applied"));
    }

    @Test
    void cleanAndMigrateIsRefusedByDefaultAndDropsNothing() {
        SchemaFake schema = new SchemaFake("1", "2");
        MigrationExtension ext = booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));

        String result = run(ext, "clean-and-migrate", "default");

        assertEquals("default: clean refused, nothing dropped; set vidocq.migration.cleanDisabled=false to allow it",
                result);
        assertEquals(0, schema.cleans);
        assertEquals(List.of("1", "2"), schema.applied);
        assertEquals("disabled: vidocq.migration.cleanDisabled=false allows it",
                sampled(ext).groups().get("default").text("clean"));
    }

    @Test
    void aNamedDatasourceNamesItsOwnCleanKey() {
        MigrationExtension ext = booted(new SchemaFake("1"), Map.of(
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit"));
        assertEquals("audit: clean refused, nothing dropped; set vidocq.migration.audit.cleanDisabled=false to"
                + " allow it", run(ext, "clean-and-migrate", "audit"));
    }

    @Test
    void cleanAndMigrateWithTheKeyDropsTheSchemaAndMigratesItAgain() {
        SchemaFake schema = new SchemaFake("1", "2");
        MigrationExtension ext = booted(schema, Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def", "vidocq.migration.cleanDisabled", "false"));

        String result = run(ext, "clean-and-migrate", "default");

        assertEquals("default: schema cleaned, 2 migrations applied, schema at version 2", result);
        assertEquals(1, schema.cleans);
        RecordingSample group = sampled(ext).groups().get("default");
        assertEquals("clean-and-migrate: 2 applied", group.text("last-run"));
        assertEquals("allowed", group.text("clean"));
    }

    @Test
    void aBackendThatCannotCleanOrListSaysSo() {
        MigrationExtension ext = booted(new FakeMigrator("flyway"), Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def", "vidocq.migration.cleanDisabled", "false"));
        RecordingSample group = sampled(ext).groups().get("default");
        assertEquals("flyway cannot list the migrations", group.text("applied"));
        assertEquals("default: flyway cannot clean a schema, nothing dropped", run(ext, "clean-and-migrate", "default"));
    }

    @Test
    void aFailedMigrationIsShownByItsClassAndRethrown() {
        SchemaFake schema = new SchemaFake("1");
        MigrationExtension ext = booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        schema.failure = new IllegalStateException("jdbc:h2:mem:def password=s3cr3t");

        assertThrows(IllegalStateException.class, () -> run(ext, "migrate", "default"));

        RecordingSample group = sampled(ext).groups().get("default");
        assertEquals("migrate: failed (IllegalStateException)", group.text("last-run"));
        assertEquals("1", group.text("version"), "the previous result stays");
        assertFalse(group.toString().contains("s3cr3t"));
    }

    @Test
    void thePasswordNeverReachesThePanel() {
        SchemaFake schema = new SchemaFake("1");
        MigrationExtension ext = booted(schema, Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def", "vidocq.pool.username", "sa",
                "vidocq.pool.password", "s3cr3t", "vidocq.migration.cleanDisabled", "false"));
        schema.available.add("2");
        List<String> results = List.of(run(ext, "migrate", "default"), run(ext, "clean-and-migrate", "default"));
        RecordingSample sample = sampled(ext);
        RecordedSection section = new RecordedSection();
        ext.contribute(new ReportContext(Verbosity.DETAILED), section);

        assertEquals("s3cr3t", schema.lastPassword, "the backend gets it");
        for (String text : List.of(results.toString(), sample.toString(), section.summary, section.rows.toString(),
                ext.outcomes().toString(), schema.lastTarget.toString())) {
            assertFalse(text.contains("s3cr3t"), text);
        }
    }

    @Test
    void aStoppedExtensionShowsNothingAndRunsNothing() {
        SchemaFake schema = new SchemaFake("1");
        MigrationExtension ext = booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        ext.onStop();

        assertTrue(sampled(ext).isEmpty());
        assertThrows(IllegalStateException.class, () -> run(ext, "migrate", "default"));
        assertEquals(1, schema.migrations, "the boot's only");
    }

    @Test
    void anUnknownDatasourceIsRefused() {
        MigrationExtension ext = booted(new SchemaFake("1"), Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        assertThrows(IllegalArgumentException.class, () -> ext.migrateNow("audit"));
    }

    private static MigrationExtension booted(SchemaMigrator backend, Map<String, String> config) {
        MigrationExtension ext = new MigrationExtension(() -> List.of(backend));
        ext.configure(MapConfig.of(config));
        ext.beforeStart(null);
        ext.onStart(new StartContext(LaunchMode.DEV));
        return ext;
    }

    private static String run(MigrationExtension ext, String action, String datasource) {
        return ext.actions().stream().filter(a -> a.id().equals(action)).findFirst().orElseThrow()
                .run().apply(Map.of("datasource", datasource));
    }

    private static RecordingSample sampled(MigrationExtension ext) {
        RecordingSample sample = new RecordingSample();
        ext.sample(sample);
        return sample;
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

    /**
     * A schema in memory: the versions its locations hold, those applied, oldest first, and how often it was
     * migrated and cleaned; each migration is described {@code V<version>} and installed at a fixed instant.
     */
    private static final class SchemaFake implements SchemaMigrator {
        final List<String> available = new ArrayList<>();
        final List<String> applied = new ArrayList<>();
        int migrations;
        int cleans;
        RuntimeException failure;
        boolean applyNothing;
        String lastPassword;
        MigrationTarget lastTarget;

        SchemaFake(String... versions) {
            available.addAll(List.of(versions));
        }

        @Override public String engine() { return "flyway"; }

        @Override public MigrationResult migrate(MigrationTarget target) {
            lastPassword = target.password();
            lastTarget = target;
            if (failure != null) {
                throw failure;
            }
            migrations++;
            List<String> pending = applyNothing ? List.of() : pending();
            applied.addAll(pending);
            return new MigrationResult(pending.size(), applied.isEmpty() ? "(none)" : applied.getLast(), false);
        }

        @Override public MigrationInfo info(MigrationTarget target) {
            return new MigrationInfo(
                    applied.stream().map(v -> new MigrationInfo.Migration(v, "V" + v, "SQL",
                            "2026-09-23T10:00:00Z", "Success")).toList(),
                    pending().stream().map(v -> new MigrationInfo.Migration(v, "V" + v, "SQL", null, "Pending"))
                            .toList());
        }

        @Override public void clean(MigrationTarget target) {
            cleans++;
            applied.clear();
        }

        private List<String> pending() {
            return available.stream().filter(v -> !applied.contains(v)).toList();
        }
    }

    private record StartContext(LaunchMode launchMode) implements ExtensionContext {
        @Override public VaubanContainer container() { return null; }
        @Override public VidocqConfiguration configuration() { return null; }
        @Override public VidocqConfig config() { return null; }
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
