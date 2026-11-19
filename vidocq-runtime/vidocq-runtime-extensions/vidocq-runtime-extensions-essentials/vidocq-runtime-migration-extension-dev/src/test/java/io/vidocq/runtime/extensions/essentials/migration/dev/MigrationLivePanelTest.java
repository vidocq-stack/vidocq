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
package io.vidocq.runtime.extensions.essentials.migration.dev;

import io.vidocq.runtime.extensions.essentials.migration.MigrationExtension;
import io.vidocq.runtime.extensions.essentials.migration.MigrationInfo;
import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
import io.vidocq.runtime.extensions.essentials.migration.live.MigrationControl.LiveOutcome;
import io.vidocq.runtime.extensions.essentials.migration.live.MigrationLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code migration} section, live: {@link MigrationLivePanel} reads {@link MigrationLive}, the control
 * {@link MigrationExtension} publishes once a migrator is selected and clears first thing in its {@code onStop}
 * (Vidocq/vidocq#143).
 */
class MigrationLivePanelTest {

    private final MigrationLivePanel panel = new MigrationLivePanel();

    /** A dev reload never shows the previous boot's control: never leak one test into the next either. */
    @AfterEach
    void clearTheLiveHolder() {
        MigrationLive.clear();
    }

    private static MigrationExtension booted(SchemaMigrator backend, Map<String, String> config) {
        MigrationExtension ext = newExtension(() -> List.of(backend));
        ext.configure(new MapConfig(config));
        ext.beforeStart(null);
        ext.onStart(new StartContext(LaunchMode.DEV));
        return ext;
    }

    /**
     * Builds a {@link MigrationExtension} with a fake backend, exactly as the runtime module's own tests do —
     * through its package-private constructor, reached here by reflection since this module cannot see it
     * directly and the constructor must stay package-private (Vidocq/vidocq#143: it is a test seam, not part
     * of the runtime's public API). Surefire runs this module's tests off the module path (the pom's
     * {@code useModulePath=false}), so {@code setAccessible(true)} needs no {@code opens} in the runtime module's
     * {@code module-info}; on a real module path this would need one, which the runtime module does not grant.
     */
    private static MigrationExtension newExtension(Supplier<List<SchemaMigrator>> backends) {
        try {
            Constructor<MigrationExtension> ctor = MigrationExtension.class.getDeclaredConstructor(Supplier.class);
            ctor.setAccessible(true);
            return ctor.newInstance(backends);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("MigrationExtension(Supplier) is no longer reachable by reflection", e);
        }
    }

    private String run(String action, String datasource) {
        return panel.actions().stream().filter(a -> a.id().equals(action)).findFirst().orElseThrow()
                .run().apply(Map.of("datasource", datasource));
    }

    private RecordingSample sampled() {
        RecordingSample sample = new RecordingSample();
        panel.sample(sample);
        return sample;
    }

    @Test
    void itMakesTheMigrationSectionLiveWithNoCharts() {
        assertEquals("migration", panel.id());
        assertTrue(panel.charts().isEmpty());
    }

    @Test
    void theActionsTakeAMigratedDatasourceByName() {
        booted(new SchemaFake(), Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit"));

        List<PanelAction> actions = panel.actions();

        assertEquals(List.of("migrate", "clean-and-migrate"), actions.stream().map(PanelAction::id).toList());
        assertEquals(List.of("Migrate now", "Clean and migrate"), actions.stream().map(PanelAction::label).toList());
        assertNull(actions.get(0).confirmation());
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
        MigrationExtension idle = newExtension(List::of);
        idle.configure(new MapConfig(Map.of()));
        assertTrue(panel.actions().isEmpty());
        assertTrue(sampled().isEmpty());
    }

    @Test
    void migrateAppliesWhatWasAddedAfterTheBootAndReplacesTheOutcome() {
        SchemaFake schema = new SchemaFake("1", "2");
        booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        schema.available.add("3");

        String result = run("migrate", "default");

        assertEquals("default: 1 migration applied, schema at version 3", result);
        RecordingSample group = sampled().groups().get("default");
        assertEquals("3", group.text("version"));
        assertEquals("migrate: 1 applied", group.text("last-run"));
        assertEquals("[[1, V1, SQL, 2026-09-23T10:00:00Z, Success], [2, V2, SQL, 2026-09-23T10:00:00Z, Success],"
                + " [3, V3, SQL, 2026-09-23T10:00:00Z, Success]]", group.text("applied"));
        assertEquals("[]", group.text("pending"));

        LiveOutcome kept = MigrationLive.control().outcomes().get(0);
        assertEquals("3", kept.version(), "the outcome the panel reads is replaced too");
    }

    @Test
    void theBootListsTheMigrationsInADevLaunchOnly() {
        SchemaFake schema = new SchemaFake("1");
        booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        RecordingSample devGroup = sampled().groups().get("default");
        assertEquals("table", devGroup.kind("applied"));
        assertEquals("boot: 1 applied", devGroup.text("last-run"));

        MigrationExtension prod = newExtension(() -> List.of(new SchemaFake("1")));
        prod.configure(new MapConfig(Map.of("vidocq.pool.url", "jdbc:h2:mem:def")));
        prod.beforeStart(null);
        prod.onStart(new StartContext(LaunchMode.PROD));
        RecordingSample prodGroup = sampled().groups().get("default");
        assertEquals("absent", prodGroup.kind("applied"));
        assertEquals("listed in a dev launch only", prodGroup.text("applied"));
        assertEquals("absent", prodGroup.kind("pending"));
    }

    @Test
    void theMigrationsAreListedByAnActionNeverBySample() {
        SchemaFake schema = new SchemaFake("1");
        booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        schema.available.add("2");
        assertEquals("[]", sampled().groups().get("default").text("pending"), "sample reads no database");

        schema.applyNothing = true;
        assertEquals("default: 0 migrations applied, schema at version 1", run("migrate", "default"));

        RecordingSample group = sampled();
        assertEquals("[[2, V2, SQL, , Pending]]", group.groups().get("default").text("pending"));
        assertEquals("[[1, V1, SQL, 2026-09-23T10:00:00Z, Success]]", group.groups().get("default").text("applied"));
    }

    @Test
    void cleanAndMigrateIsRefusedByDefaultAndDropsNothing() {
        SchemaFake schema = new SchemaFake("1", "2");
        booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));

        String result = run("clean-and-migrate", "default");

        assertEquals("default: clean refused, nothing dropped; set vidocq.migration.cleanDisabled=false to allow it",
                result);
        assertEquals(0, schema.cleans);
        assertEquals(List.of("1", "2"), schema.applied);
        assertEquals("disabled: vidocq.migration.cleanDisabled=false allows it",
                sampled().groups().get("default").text("clean"));
    }

    @Test
    void aNamedDatasourceNamesItsOwnCleanKey() {
        booted(new SchemaFake("1"), Map.of(
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit"));
        assertEquals("audit: clean refused, nothing dropped; set vidocq.migration.audit.cleanDisabled=false to"
                + " allow it", run("clean-and-migrate", "audit"));
    }

    @Test
    void cleanAndMigrateWithTheKeyDropsTheSchemaAndMigratesItAgain() {
        SchemaFake schema = new SchemaFake("1", "2");
        booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def", "vidocq.migration.cleanDisabled", "false"));

        String result = run("clean-and-migrate", "default");

        assertEquals("default: schema cleaned, 2 migrations applied, schema at version 2", result);
        assertEquals(1, schema.cleans);
        RecordingSample group = sampled().groups().get("default");
        assertEquals("clean-and-migrate: 2 applied", group.text("last-run"));
        assertEquals("allowed", group.text("clean"));
    }

    @Test
    void aBackendThatCannotCleanOrListSaysSo() {
        booted(new FakeMigrator("flyway"), Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def", "vidocq.migration.cleanDisabled", "false"));
        RecordingSample group = sampled().groups().get("default");
        assertEquals("flyway cannot list the migrations", group.text("applied"));
        assertEquals("default: flyway cannot clean a schema, nothing dropped", run("clean-and-migrate", "default"));
    }

    @Test
    void aFailedMigrationIsShownByItsClassAndRethrown() {
        SchemaFake schema = new SchemaFake("1");
        booted(schema, Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        schema.failure = new IllegalStateException("jdbc:h2:mem:def password=s3cr3t");

        assertThrows(IllegalStateException.class, () -> run("migrate", "default"));

        RecordingSample group = sampled().groups().get("default");
        assertEquals("migrate: failed (IllegalStateException)", group.text("last-run"));
        assertEquals("1", group.text("version"), "the previous result stays");
        assertFalse(group.toString().contains("s3cr3t"));
    }

    @Test
    void thePasswordNeverReachesThePanel() {
        SchemaFake schema = new SchemaFake("1");
        booted(schema, Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def", "vidocq.pool.username", "sa",
                "vidocq.pool.password", "s3cr3t", "vidocq.migration.cleanDisabled", "false"));
        schema.available.add("2");
        List<String> results = List.of(run("migrate", "default"), run("clean-and-migrate", "default"));
        RecordingSample sample = sampled();

        assertEquals("s3cr3t", schema.lastPassword, "the backend gets it");
        for (String text : List.of(results.toString(), sample.toString())) {
            assertFalse(text.contains("s3cr3t"), text);
        }
    }

    @Test
    void afterOnStopThePanelShowsNothingAndOffersNoAction() {
        MigrationExtension ext = booted(new SchemaFake("1"), Map.of("vidocq.pool.url", "jdbc:h2:mem:def"));
        assertFalse(sampled().isEmpty());
        assertFalse(panel.actions().isEmpty());

        ext.onStop();

        assertTrue(sampled().isEmpty(), "a poll during a dev reload reads no closed control");
        assertTrue(panel.actions().isEmpty(), "no action without a control");
    }

    // ── test doubles ─────────────────────────────────────────────────────────

    private record MapConfig(Map<String, String> data) implements VidocqConfiguration {
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

    /**
     * A schema in memory: the versions its locations hold, those applied, oldest first, and how often it was
     * migrated and cleaned; each migration is described {@code V<version>} and installed at a fixed instant.
     */
    private static final class SchemaFake implements SchemaMigrator {
        final List<String> available = new ArrayList<>();
        final List<String> applied = new ArrayList<>();
        int cleans;
        RuntimeException failure;
        boolean applyNothing;
        String lastPassword;

        SchemaFake(String... versions) {
            available.addAll(List.of(versions));
        }

        @Override public String engine() { return "flyway"; }

        @Override public MigrationResult migrate(MigrationTarget target) {
            lastPassword = target.password();
            if (failure != null) {
                throw failure;
            }
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
}
