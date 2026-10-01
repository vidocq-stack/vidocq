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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import io.vidocq.runtime.extensions.essentials.migration.MigrationInfo;
import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link LiquibaseSchemaMigrator} using an in-memory H2 database.
 */
class LiquibaseSchemaMigratorTest {

    @Test
    void migratesH2FromChangelog() throws Exception {
        String url = url();
        MigrationResult r = new LiquibaseSchemaMigrator().migrate(target(url, "db/testchangelog/db.changelog-master.xml"));
        assertEquals(1, r.applied());
        assertFalse(r.nothingFound());
        assertTrue(count(url, "widget") >= 0);
    }

    // ── Liquibase analytics ───────────────────────────────────────────────────

    @Test
    void aMigrationRunsWithLiquibaseAnalyticsOff() throws Exception {
        // Liquibase 4.30+ sends usage data to Liquibase by default for OSS users
        // (liquibase.analytics.enabled). A Vidocq application does not talk to a third party
        // unless it says so: read the effective value while Liquibase reads the changelog.
        var seen = new java.util.ArrayList<Boolean>();
        var spying = new ClassLoaderResourceAccessor(getClass().getClassLoader()) {
            @Override
            public List<liquibase.resource.Resource> getAll(String path) throws java.io.IOException {
                seen.add(liquibase.analytics.configuration.AnalyticsArgs.ENABLED.getCurrentValue());
                return super.getAll(path);
            }
        };

        new LiquibaseSchemaMigrator().migrate(target(url(), "db/testchangelog/db.changelog-master.xml"), spying);

        // Unset (null) means "enabled for OSS users" to Liquibase: only an explicit false turns it off.
        assertFalse(seen.isEmpty(), "the changelog was never read");
        assertTrue(seen.stream().allMatch(Boolean.FALSE::equals), "analytics not off during the migration: " + seen);
    }

    @Test
    void aConfiguredAnalyticsChoiceIsKept() throws Exception {
        String key = liquibase.analytics.configuration.AnalyticsArgs.ENABLED.getKey();
        System.setProperty(key, "true");
        try {
            assertTrue(LiquibaseSchemaMigrator.withoutAnalyticsByDefault(
                    liquibase.analytics.configuration.AnalyticsArgs.ENABLED::getCurrentValue));
        } finally {
            System.clearProperty(key);
        }
    }

    // ── vidocq#96 ─────────────────────────────────────────────────────────────

    @Test
    void liquibaseCannotOpenTheUrlsOfTheApplicationLayersLoader() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new LiquibaseSchemaMigrator()
                .migrate(target(url(), "db/testchangelog/db.changelog-master.xml"),
                        new ClassLoaderResourceAccessor(new OpaqueUrlLoader())));
        assertTrue(e.getMessage().contains("unknown protocol: vauban"), e.getMessage());
    }

    @Test
    void theApplicationAccessorReadsAChangelogByName() throws Exception {
        String url = url();
        MigrationResult r = new LiquibaseSchemaMigrator().migrate(target(url, "db/testchangelog/db.changelog-master.xml"),
                new ApplicationResourceAccessor(directory -> List.of(), new OpaqueUrlLoader()));
        assertEquals(1, r.applied());
        assertEquals(0, count(url, "widget"));
    }

    @Test
    void includeAllIsListedFromTheApplicationLayer() throws Exception {
        String url = url();
        Map<String, List<String>> files = Map.of(
                "db/testincludeall/changes", List.of("db/testincludeall/changes/001-gadget.xml"));
        MigrationResult r = new LiquibaseSchemaMigrator().migrate(target(url, "db/testincludeall/db.changelog-master.xml"),
                new ApplicationResourceAccessor(directory -> files.getOrDefault(directory, List.of()),
                        new OpaqueUrlLoader()));
        assertEquals(1, r.applied());
        assertEquals(0, count(url, "gadget"));
    }

    @Test
    void aChangelogWithoutAChangesetOnAFreshSchemaIsNothingFound() {
        MigrationResult r = new LiquibaseSchemaMigrator().migrate(target(url(), "db/testempty/db.changelog-master.xml"));
        assertEquals(0, r.applied());
        assertTrue(r.nothingFound(), "no changeset to run, none ever run: what VIDOCQ-MIG-001 reports");
    }

    @Test
    void anUpToDateRestartIsNotNothingFound() {
        MigrationTarget target = target(url(), "db/testchangelog/db.changelog-master.xml");
        new LiquibaseSchemaMigrator().migrate(target);

        MigrationResult restart = new LiquibaseSchemaMigrator().migrate(target);

        assertEquals(0, restart.applied());
        assertFalse(restart.nothingFound());
    }

    // ── vidocq#120: info and clean ─────────────────────────────────────────────

    @Test
    void infoListsThePendingThenTheAppliedChangesets() {
        MigrationTarget target = target(url(), "db/testchangelog/db.changelog-master.xml");
        LiquibaseSchemaMigrator migrator = new LiquibaseSchemaMigrator();

        MigrationInfo before = migrator.info(target);
        migrator.migrate(target);
        MigrationInfo after = migrator.info(target);

        assertTrue(before.applied().isEmpty());
        assertEquals(1, before.pending().size());
        MigrationInfo.Migration pending = before.pending().get(0);
        assertEquals("1", pending.version(), "a changeset's id stands for its version");
        assertTrue(pending.description().contains("by vidocq"), pending.description());
        assertEquals("changeset", pending.type());
        assertEquals("", pending.installedOn());
        assertEquals("PENDING", pending.state());

        assertTrue(after.pending().isEmpty());
        assertEquals(1, after.applied().size());
        assertEquals("1", after.applied().get(0).version());
        assertEquals("EXECUTED", after.applied().get(0).state());
        assertFalse(after.applied().get(0).installedOn().isEmpty());
    }

    @Test
    void cleanIsRefusedWhileCleanIsDisabledAndDropsNothing() throws Exception {
        String url = url();
        MigrationTarget target = target(url, "db/testchangelog/db.changelog-master.xml");
        new LiquibaseSchemaMigrator().migrate(target);

        assertThrows(IllegalStateException.class, () -> new LiquibaseSchemaMigrator().clean(target));

        assertEquals(0, count(url, "widget"), "the table is still there");
    }

    @Test
    void cleanDropsTheSchemaWhenCleanIsNotDisabled() throws Exception {
        String url = url();
        MigrationTarget target = new MigrationTarget("default", url, "sa", "",
                List.of("db/testchangelog/db.changelog-master.xml"), false, false);
        LiquibaseSchemaMigrator migrator = new LiquibaseSchemaMigrator();
        migrator.migrate(target);

        migrator.clean(target);

        assertThrows(SQLException.class, () -> count(url, "widget"));
        assertEquals(1, migrator.info(target).pending().size(), "the changelog table is gone too");
        assertEquals(1, migrator.migrate(target).applied());
        assertEquals(0, count(url, "widget"));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static String url() {
        return "jdbc:h2:mem:lb-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static MigrationTarget target(String url, String changelog) {
        return new MigrationTarget("default", url, "sa", "", List.of(changelog));
    }

    private static int count(String url, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
