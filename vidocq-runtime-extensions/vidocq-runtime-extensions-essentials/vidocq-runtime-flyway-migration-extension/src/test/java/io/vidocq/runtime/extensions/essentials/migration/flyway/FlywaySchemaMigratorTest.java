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
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link FlywaySchemaMigrator} using an in-memory H2 database.
 */
class FlywaySchemaMigratorTest {

    @TempDir
    Path dir;

    @Test
    void migratesH2FromClasspathLocation() throws Exception {
        String url = url();
        MigrationResult r = new FlywaySchemaMigrator().migrate(
                new MigrationTarget("default", url, "sa", "", List.of("classpath:db/testmigration")));
        assertEquals(1, r.applied());
        assertEquals("1", r.version());
        assertFalse(r.nothingFound());
        assertEquals(0, count(url, "widget"));
    }

    // ── vidocq#96 ─────────────────────────────────────────────────────────────

    @Test
    void aLoaderThatListsNoDirectoryLeavesFlywaysScannerWithNothing() {
        MigrationResult r = new FlywaySchemaMigrator().migrate(target(url(), "classpath:db/testmigration"),
                null, new NameOnlyLoader());
        assertEquals(0, r.applied());
        assertEquals("(none)", r.version());
        assertTrue(r.nothingFound(), "no migration found, none recorded: what VIDOCQ-MIG-001 reports");
    }

    @Test
    void theApplicationLayerListsWhatTheLoaderCannot() throws Exception {
        String url = url();
        MigrationResult r = new FlywaySchemaMigrator().migrate(target(url, "classpath:db/testmigration"),
                lister(Map.of("db/testmigration", List.of("db/testmigration/V1__create_widget.sql"))),
                new NameOnlyLoader());
        assertEquals(1, r.applied());
        assertEquals("1", r.version());
        assertEquals(0, count(url, "widget"));
    }

    @Test
    void theApplicationLayerFindsJavaMigrationsByName() throws Exception {
        String url = url();
        MigrationResult r = new FlywaySchemaMigrator().migrate(target(url, "classpath:db/testjava"),
                lister(Map.of("db/testjava", List.of("db/testjava/NotAMigration.class",
                        "db/testjava/V1__CreateGadget.class"))),
                new NameOnlyLoader());
        assertEquals(1, r.applied());
        assertEquals(0, count(url, "gadget"));
    }

    @Test
    void anUpToDateRestartKeepsItsVersion() {
        String url = url();
        MigrationTarget target = target(url, "classpath:db/testmigration");
        new FlywaySchemaMigrator().migrate(target);

        MigrationResult restart = new FlywaySchemaMigrator().migrate(target);

        assertEquals(0, restart.applied());
        assertEquals("1", restart.version(), "the current version, not (none)");
        assertFalse(restart.nothingFound());
    }

    @Test
    void aHistoryOfRepeatableMigrationsAloneIsNotNothingFound() {
        String url = url();
        MigrationTarget target = target(url, "classpath:db/testrepeatable");
        assertEquals(1, new FlywaySchemaMigrator().migrate(target).applied());

        MigrationResult restart = new FlywaySchemaMigrator().migrate(target);

        assertEquals(0, restart.applied());
        assertFalse(restart.nothingFound(), "no version, and nothing run, but the history holds a migration");
    }

    @Test
    void strictModeFailsOnALocationThatHoldsNothing() {
        MigrationTarget strict = new MigrationTarget("default", url(), "sa", "",
                List.of("classpath:db/nowhere"), true);
        FlywayException scanned = assertThrows(FlywayException.class,
                () -> new FlywaySchemaMigrator().migrate(strict));
        assertTrue(scanned.getMessage().contains("Unable to resolve location classpath:db/nowhere"),
                scanned.getMessage());

        FlywayException listed = assertThrows(FlywayException.class,
                () -> new FlywaySchemaMigrator().migrate(strict, lister(Map.of()), new NameOnlyLoader()));
        assertTrue(listed.getMessage().contains("Unable to resolve location classpath:db/nowhere"),
                listed.getMessage());
    }

    @Test
    void locationsThatAreNotAllClasspathAreLeftToFlywaysScanner() throws Exception {
        Path scripts = Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(scripts.resolve("V2__create_gizmo.sql"), "CREATE TABLE gizmo (id INT PRIMARY KEY);");
        String url = url();
        MigrationResult r = new FlywaySchemaMigrator().migrate(
                new MigrationTarget("default", url, "sa", "",
                        List.of("classpath:db/testmigration", "filesystem:" + scripts)),
                lister(Map.of()), FlywaySchemaMigratorTest.class.getClassLoader());
        assertEquals(2, r.applied(), "the scanner lists both kinds; the layer's lister is not used");
        assertEquals(0, count(url, "gizmo"));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static String url() {
        return "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static MigrationTarget target(String url, String location) {
        return new MigrationTarget("default", url, "sa", "", List.of(location));
    }

    /** The files of each directory, as the application layer would list them. */
    private static Function<String, List<String>> lister(Map<String, List<String>> files) {
        return directory -> files.getOrDefault(directory, List.of());
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
