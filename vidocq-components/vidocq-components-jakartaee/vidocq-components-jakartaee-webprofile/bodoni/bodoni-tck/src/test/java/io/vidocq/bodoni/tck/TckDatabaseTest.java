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

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
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
import java.util.regex.Pattern;
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

    /**
     * A database of this test's own: never {@code mem:jstl}, which a listener-started database of the
     * same JVM would share, and which this test's {@code close()} would shut down.
     */
    private final String name = DdlFixture.uniqueName();

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
    void lineCommentsAreStrippedOutsideQuotesBeforeSplitting() {
        String script = """
                -- a header; with a semicolon
                drop table t1 ; -- trailing
                INSERT INTO t1 VALUES(1, 'a--b') ;
                """;

        assertEquals(List.of("drop table t1", "INSERT INTO t1 VALUES(1, 'a--b')"), TckDatabase.statements(script));
    }

    @Test
    void aLeadingCommentKeepsTheDropTolerated() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, "-- Bodoni's own fixture\n" + DdlFixture.SCRIPT), name);

        assertEquals(2, DdlFixture.countT1(database.url()));
    }

    @Test
    void loadsTheScriptIgnoringDropsOfMissingTables() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT), name);

        assertEquals(2, DdlFixture.countT1(database.url()));
    }

    @Test
    void failingStatementOtherThanDropStopsTheStart() throws Exception {
        // t2 is created before the failure: if the failed start left its in-memory database behind,
        // the fixture's "create table t2" below would fail.
        Path broken = DdlFixture.write(directory, "create table t2(idNum INT NOT NULL) ;\ncreate table x(");

        SQLException failure = assertThrows(SQLException.class, () -> TckDatabase.start(broken, name));

        assertTrue(failure.getMessage().contains("create table x("), failure.getMessage());
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT), name);
        assertEquals(2, DdlFixture.countT1(database.url()));
    }

    @Test
    void clientPropertiesAreNonEmptyAndTheUrlHasNoComma() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT), name);

        Map<String, String> properties = database.clientProperties();

        assertEquals(Set.of(DB_URL, "jstl.db.user", "jstl.db.password", "jstl.db.driver"),
                properties.keySet());
        assertEquals("jstl", properties.get("jstl.db.user"));
        assertEquals("jstl", properties.get("jstl.db.password"));
        assertEquals("org.h2.Driver", properties.get("jstl.db.driver"));
        String url = properties.get(DB_URL);
        assertEquals(database.url(), url);
        assertTrue(url.matches("jdbc:h2:tcp://127\\.0\\.0\\.1:\\d+/mem:" + Pattern.quote(name)), url);
        assertFalse(url.contains(","), url);
        // start(Path), which the listener uses, serves this name: the WARs' jdbc:h2:tcp://…/mem:jstl.
        assertEquals("jstl", TckDatabase.NAME);
    }

    @Test
    void anotherJvmSeesTheRowsThroughTheTcpServer() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT), name);
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
        database = TckDatabase.start(ddl, name);
        try (Connection connection = DriverManager.getConnection(database.url(), TckDatabase.USER, TckDatabase.PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO t1 VALUES(3, 'Parma')");
        }
        assertEquals(3, DdlFixture.countT1(database.url()));

        database.close();
        database = TckDatabase.start(ddl, name);

        assertEquals(2, DdlFixture.countT1(database.url()));
    }

    /**
     * The server owns the loopback port its URL names. Bound to the wildcard address, it let another
     * socket bind {@code 127.0.0.1} on the same port (macOS, {@code SO_REUSEADDR}), and a client of the
     * URL then reached that socket: "unexpected status 6148" is the header of an HTTP/2 SETTINGS frame.
     */
    @Test
    void noOtherSocketCanTakeTheLoopbackPortOfTheUrl() throws Exception {
        database = TckDatabase.start(DdlFixture.write(directory, DdlFixture.SCRIPT), name);
        InetAddress loopback = InetAddress.getByName(TckDatabase.LOOPBACK);
        int port = database.port();

        assertThrows(BindException.class, () -> new ServerSocket(port, 0, loopback).close());
        assertEquals(2, DdlFixture.countT1(database.url()));
    }

    @Test
    void onlyALoopbackBindAddressIsAccepted() {
        TckDatabase.requireLoopbackBinding(TckDatabase.LOOPBACK);

        IllegalStateException unset = assertThrows(IllegalStateException.class,
                () -> TckDatabase.requireLoopbackBinding(null));
        assertTrue(unset.getMessage().contains("h2.bindAddress"), unset.getMessage());
        assertThrows(IllegalStateException.class, () -> TckDatabase.requireLoopbackBinding("0.0.0.0"));
    }
}
