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
package io.vidocq.bodoni.tck;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.LauncherSessionListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher session listener that starts the TCK database: inert without {@code bodoni.tck.ddl}, so
 * the runner's own tests never need the official zip, and loud when the property names no file (Review
 * Focus 1), so a run never goes on with every sql and compat test failing in its setup.
 */
class TckDatabaseListenerTest {
    private static final String DB_URL = "jstl.db.url";
    private static final List<String> CLIENT_PROPERTIES =
            List.of(DB_URL, "jstl.db.user", "jstl.db.password", "jstl.db.driver");

    @TempDir
    Path directory;

    private final Map<String, String> saved = new HashMap<>();
    private TckDatabase database;

    @BeforeEach
    void saveTheClientProperties() {
        for (String name : CLIENT_PROPERTIES) {
            saved.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
    }

    @AfterEach
    void restoreTheClientProperties() throws SQLException {
        saved.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
        if (database != null) {
            database.close();
        }
    }

    @Test
    void doesNothingWithoutTheDdlProperty() throws Exception {
        assertTrue(TckDatabaseListener.startIfConfigured(null).isEmpty());
        assertTrue(TckDatabaseListener.startIfConfigured(" ").isEmpty());
        assertNull(System.getProperty(DB_URL));
    }

    @Test
    void missingDdlFailsNamingTheFileAndTheScript() {
        String ddl = directory.resolve("absent.ddl.jstl.sql").toString();

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> TckDatabaseListener.startIfConfigured(ddl));

        assertTrue(failure.getMessage().contains(ddl), failure.getMessage());
        assertTrue(failure.getMessage().contains("run-official-tck-tags-3.0.sh"), failure.getMessage());
        assertNull(System.getProperty(DB_URL));
    }

    @Test
    void configuredDdlStartsTheDatabaseAndPublishesTheClientProperties() throws Exception {
        String ddl = DdlFixture.write(directory, DdlFixture.SCRIPT).toString();

        database = TckDatabaseListener.startIfConfigured(ddl, DdlFixture.uniqueName()).orElseThrow();

        assertEquals(database.url(), System.getProperty(DB_URL));
        database.clientProperties().forEach((name, value) -> assertEquals(value, System.getProperty(name), name));
        assertEquals(2, DdlFixture.countT1(System.getProperty(DB_URL)));
    }

    @Test
    void isRegisteredAsALauncherSessionListener() {
        assertTrue(ServiceLoader.load(LauncherSessionListener.class).stream()
                .map(ServiceLoader.Provider::type)
                .anyMatch(TckDatabaseListener.class::equals));
    }
}
