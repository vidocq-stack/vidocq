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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TCK database: a Derby-style DDL loaded into an in-memory H2 database and served over TCP on an
 * ephemeral loopback port, so that every WAR host process reaches the same rows (Review Focus 2 and 5).
 */
class TckDatabaseTest {
    private static final String DB_URL = "jstl.db.url";

    @TempDir
    Path directory;

    private TckDatabase database;

    @AfterEach
    void closeTheDatabase() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void statementsSplitOnSemicolonsAndDropBlanks() {
        assertEquals(List.of("a", "b"), TckDatabase.statements("a ;\n\n b ;  \n"));
    }

    @Test
    void loadsTheScriptIgnoringDropsOfMissingTables() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT));

        assertEquals(2, DdlFixture.countT1(database.url()));
    }

    @Test
    void failingStatementOtherThanDropStopsTheStart() throws Exception {
        // t2 is created before the failure: if the failed start left its in-memory database behind,
        // the fixture's "create table t2" below would fail.
        Path broken = DdlFixture.write(directory, "create table t2(idNum INT NOT NULL) ;\ncreate table x(");

        SQLException failure = assertThrows(SQLException.class, () -> TckDatabase.start(broken));

        assertTrue(failure.getMessage().contains("create table x("), failure.getMessage());
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT));
        assertEquals(2, DdlFixture.countT1(database.url()));
    }

    @Test
    void clientPropertiesAreNonEmptyAndTheUrlHasNoComma() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT));

        Map<String, String> properties = database.clientProperties();

        assertEquals(Set.of(DB_URL, "jstl.db.user", "jstl.db.password", "jstl.db.driver"),
                properties.keySet());
        assertEquals("jstl", properties.get("jstl.db.user"));
        assertEquals("jstl", properties.get("jstl.db.password"));
        assertEquals("org.h2.Driver", properties.get("jstl.db.driver"));
        String url = properties.get(DB_URL);
        assertEquals(database.url(), url);
        assertTrue(url.matches("jdbc:h2:tcp://127\\.0\\.0\\.1:\\d+/mem:jstl"), url);
        assertFalse(url.contains(","), url);
    }

    @Test
    void anotherJvmSeesTheRowsThroughTheTcpServer() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT));
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path out = directory.resolve("probe.out");
        Path err = directory.resolve("probe.err");

        Process probe = new ProcessBuilder(java.toString(), "-cp", classPath, RemoteProbe.class.getName(),
                database.url(), TckDatabase.USER, TckDatabase.PASSWORD)
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        boolean exited = probe.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            probe.destroyForcibly();
        }

        assertTrue(exited, "RemoteProbe did not exit within 60 s");
        String errors = Files.readString(err);
        assertEquals(0, probe.exitValue(), errors);
        assertEquals("rows=2 isolation=2 dob=1999-05-05", Files.readString(out).strip(), errors);
    }

    @Test
    void restartGivesAFreshDatabase() throws Exception {
        Path ddl = DdlFixture.write(directory, DdlFixture.SCRIPT);
        database = TckDatabase.start(ddl);
        try (Connection connection = DriverManager.getConnection(database.url(), TckDatabase.USER, TckDatabase.PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO t1 VALUES(3, 'Parma')");
        }
        assertEquals(3, DdlFixture.countT1(database.url()));

        database.close();
        database = TckDatabase.start(ddl);

        assertEquals(2, DdlFixture.countT1(database.url()));
    }
}
