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

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The migrations of an application layer, as Flyway reads them (vidocq#96). */
class ApplicationLayerMigrationsTest {

    private static final Location[] TEST_MIGRATION = {new Location("classpath:db/testmigration")};

    @Test
    void aScriptKeepsTheNameFlywaysOwnScannerGivesIt() throws Exception {
        var migrations = new ApplicationLayerMigrations(TEST_MIGRATION,
                directory -> List.of("db/testmigration/V1__create_widget.sql"), new NameOnlyLoader(),
                StandardCharsets.UTF_8, false);

        List<LoadableResource> scripts = List.copyOf(migrations.getResources("V", new String[]{".sql"}));

        assertEquals(1, scripts.size());
        LoadableResource script = scripts.get(0);
        assertEquals("V1__create_widget.sql", script.getRelativePath(), "relative to its location, as in the history");
        assertEquals("V1__create_widget.sql", script.getFilename());
        assertEquals("db/testmigration/V1__create_widget.sql", script.getAbsolutePath());
        try (BufferedReader in = new BufferedReader(script.read())) {
            assertTrue(in.lines().collect(Collectors.joining("\n")).contains("CREATE TABLE widget"));
        }
        assertEquals(script, migrations.getResource("v1__CREATE_widget.sql"), "looked up ignoring case");
        assertNull(migrations.getResource("V9__missing.sql"));
    }

    @Test
    void onlyConcreteJavaMigrationsAreClasses() {
        var migrations = new ApplicationLayerMigrations(new Location[]{new Location("classpath:db/testjava")},
                directory -> List.of("db/testjava/NotAMigration.class", "db/testjava/V1__CreateGadget.class"),
                new NameOnlyLoader(), StandardCharsets.UTF_8, false);

        assertEquals(List.of(db.testjava.V1__CreateGadget.class), List.copyOf(migrations.getClasses()));
    }

    @Test
    void anEmptyLocationFailsOnlyWhenAskedTo() {
        assertTrue(new ApplicationLayerMigrations(TEST_MIGRATION, directory -> List.of(), new NameOnlyLoader(),
                StandardCharsets.UTF_8, false).getResources("V", new String[]{".sql"}).isEmpty());
        FlywayException e = assertThrows(FlywayException.class, () -> new ApplicationLayerMigrations(TEST_MIGRATION,
                directory -> List.of(), new NameOnlyLoader(), StandardCharsets.UTF_8, true));
        assertEquals("Unable to resolve location classpath:db/testmigration.", e.getMessage());
    }

    @Test
    void aFileNameMatchesAsFlywayMatchesIt() {
        String[] sql = {".sql"};
        assertTrue(ApplicationLayerMigrations.matches("V1__init.sql", "V", sql));
        assertTrue(ApplicationLayerMigrations.matches("V1__init.SQL", "V", sql), "the suffix ignores case");
        assertFalse(ApplicationLayerMigrations.matches("v1__init.sql", "V", sql), "the prefix does not");
        assertFalse(ApplicationLayerMigrations.matches("V.sql", "V", sql), "nothing between prefix and suffix");
        assertFalse(ApplicationLayerMigrations.matches("R__view.sql", "V", sql));
        assertTrue(ApplicationLayerMigrations.matches("beforeMigrate.sql", "", sql), "an empty prefix matches all");
    }
}
