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

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
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
