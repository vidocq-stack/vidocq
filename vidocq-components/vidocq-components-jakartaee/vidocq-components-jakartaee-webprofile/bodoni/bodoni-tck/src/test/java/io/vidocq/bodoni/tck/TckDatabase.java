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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.h2.tools.Server;

/**
 * The database of the Tags TCK's sql and compat classes: the official Derby DDL loaded unchanged into
 * an in-memory H2 database of the test JVM (plain H2, no compatibility mode), served by an H2 TCP server
 * on an ephemeral port. Every WAR runs in its own host process and reaches the rows through
 * {@link #url()}.
 *
 * <p>A keeper connection holds the in-memory database open ({@code DB_CLOSE_DELAY=-1}); {@link #close()}
 * shuts it down, so a later {@link #start(Path)} in the same JVM sees only the DDL's rows. The server
 * accepts local connections only (no {@code -tcpAllowOthers}) and creates no database on demand (no
 * {@code -ifNotExists}).
 */
final class TckDatabase implements AutoCloseable {
    static final String NAME = "jstl";
    static final String USER = "jstl";
    static final String PASSWORD = "jstl";
    static final String DRIVER = "org.h2.Driver";

    private static final String DROP_TABLE = "drop table";

    private final String name;
    private final Connection keeper;
    private final Server server;

    private TckDatabase(String name, Connection keeper, Server server) {
        this.name = name;
        this.keeper = keeper;
        this.server = server;
    }

    /**
     * Loads {@code ddl} into a fresh in-memory database {@value #NAME} and starts the TCP server. A
     * failing {@code drop table} is ignored (the official DDL drops its tables first, which fails on a
     * fresh database); any other failure shuts the database down and stops the start.
     */
    static TckDatabase start(Path ddl) throws IOException, SQLException {
        return start(ddl, NAME);
    }

    /**
     * {@link #start(Path)} under another in-memory database name: the runner's own tests use their own,
     * so that they never share, nor shut down, the {@value #NAME} database of the listener.
     */
    static TckDatabase start(Path ddl, String name) throws IOException, SQLException {
        List<String> statements = statements(Files.readString(ddl));
        Connection keeper = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", USER, PASSWORD);
        Server server;
        try {
            load(keeper, statements);
            server = Server.createTcpServer("-tcpPort", "0").start();
        } catch (SQLException | RuntimeException failure) {
            shutDownAfter(keeper, failure);
            throw failure;
        }
        var database = new TckDatabase(name, keeper, server);
        String url = database.url();
        if (url.indexOf(',') >= 0) {
            database.close();
            throw new IllegalStateException("The TCK database URL holds a comma, which the JDBC-string pages"
                    + " would split: " + url);
        }
        return database;
    }

    /**
     * The script without its {@code --} line comments, split on {@code ;}, each statement stripped,
     * blank ones dropped. Stripping comments first keeps a commented {@code drop table} a tolerated
     * {@code drop table}, and a comment's {@code ;} from splitting.
     */
    static List<String> statements(String script) {
        return Arrays.stream(withoutLineComments(script).split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    /** Removes each {@code --} that is outside a {@code '…'} literal, up to the end of its line. */
    private static String withoutLineComments(String script) {
        var kept = new StringBuilder(script.length());
        boolean quoted = false;
        int i = 0;
        while (i < script.length()) {
            if (!quoted && script.startsWith("--", i)) {
                int end = script.indexOf('\n', i);
                i = end < 0 ? script.length() : end;
            } else {
                char c = script.charAt(i);
                if (c == '\'') {
                    quoted = !quoted;
                }
                kept.append(c);
                i++;
            }
        }
        return kept.toString();
    }

    /** The URL the WARs connect with: loopback, the server's bound port, the in-memory database. */
    String url() {
        return "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/mem:" + name;
    }

    /** The four non-empty system properties {@code SqlUrlClient} and {@code CompatAbstractUrlClient} need. */
    Map<String, String> clientProperties() {
        return Map.of(
                "jstl.db.url", url(),
                "jstl.db.user", USER,
                "jstl.db.password", PASSWORD,
                "jstl.db.driver", DRIVER);
    }

    /** Stops the server, then shuts the in-memory database down and closes the keeper. */
    @Override
    public void close() throws SQLException {
        server.stop();
        shutDown(keeper);
    }

    private static void load(Connection keeper, List<String> statements) throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            for (String sql : statements) {
                execute(statement, sql);
            }
        }
    }

    private static void execute(Statement statement, String sql) throws SQLException {
        try {
            statement.execute(sql);
        } catch (SQLException failure) {
            if (!sql.toLowerCase(Locale.ROOT).startsWith(DROP_TABLE)) {
                throw new SQLException("TCK DDL statement failed: " + sql, failure);
            }
        }
    }

    private static void shutDownAfter(Connection keeper, Exception failure) {
        try {
            shutDown(keeper);
        } catch (SQLException suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    private static void shutDown(Connection keeper) throws SQLException {
        try (keeper; Statement statement = keeper.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
