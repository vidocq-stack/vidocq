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
package io.vidocq.runtime.it.noether.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import javax.sql.XADataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One JTA transaction over two XA pools on PostgreSQL. The unit {@code workbench} and the repository {@link Items}
 * share one connection of the default pool; the repository {@link AuditItems} writes through the pool {@code audit},
 * on another database. The commit prepares a branch per database, both of one global transaction, then commits
 * both; a failure rolls both back before anything is prepared. No branch is left in doubt either way.
 *
 * <p>The server logs every statement: the test reads the two phases there. It runs the boot of
 * {@link SharedConnectionModulePathTest}, its pools given as system properties over those of {@code vidocq.properties}.
 * Docker-gated: skips when Docker is not usable.
 */
class TwoPhaseCommitPostgresModulePathTest {

    private static final Pattern PREPARED = Pattern.compile("PREPARE TRANSACTION '([^']+)'");
    private static final Pattern COMMITTED = Pattern.compile("COMMIT PREPARED '([^']+)'");

    /** Every line the server logged. */
    private static final ToStringConsumer LOG = new ToStringConsumer();
    /** The same lines, to wait for one. */
    private static final WaitingConsumer LOGGED = new WaitingConsumer();
    /** The system properties this test set, cleared after it. */
    private static final List<String> SET = new ArrayList<>();

    private static PostgreSQLContainer postgres;
    private static VidocqBootstrap bootstrap;

    @BeforeAll
    @Timeout(240)
    static void boot() throws SQLException {
        String unusable = dockerProblem();
        assumeTrue(unusable == null, () -> "Docker is not usable here, skipping: " + unusable);
        postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_prepared_transactions=10",
                        "-c", "log_statement=all")
                .withLogConsumer(LOG.andThen(LOGGED));
        postgres.start();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE audit");
        }
        pool("vidocq.pool.", postgres.getJdbcUrl());
        pool("vidocq.pool.audit.", "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/audit");
        bootstrap = VidocqBootstrap.create().configure().start();
        for (DataSource store : List.of(pool(), audit())) {
            try (Connection connection = store.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE item (id VARCHAR(64) PRIMARY KEY, label VARCHAR(64))");
            }
        }
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
        SET.forEach(System::clearProperty);
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void both_pools_are_xa_pools_of_postgresql() throws SQLException {
        for (DataSource store : List.of(pool(), audit())) {
            assertTrue(store.isWrapperFor(XADataSource.class), "an XA pool");
            try (Connection connection = store.getConnection()) {
                assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            }
        }
    }

    @Test
    void a_commit_prepares_a_branch_per_database_then_commits_both() throws Throwable {
        String log = logged(() -> assertTrue(workbench().threeWritersOnTwoStores("two-unit", "two-repository",
                "two-audit"), "the unit and the repository read each other's writes before the commit"));

        List<String> prepared = all(PREPARED, log);
        assertEquals(2, prepared.size(), "a PREPARE TRANSACTION per database: " + prepared);
        assertEquals(globalId(prepared.get(0)), globalId(prepared.get(1)), "one global transaction");
        assertNotEquals(prepared.get(0), prepared.get(1), "two branches");
        assertEquals(Set.copyOf(prepared), Set.copyOf(all(COMMITTED, log)), "both branches committed");
        assertEquals(1, count(pool(), "two-unit"));
        assertEquals(1, count(pool(), "two-repository"));
        assertEquals(1, count(audit(), "two-audit"));
        assertEquals(0, inDoubt(), "no branch left prepared");
    }

    @Test
    void a_failure_after_the_writes_rolls_both_databases_back() throws Throwable {
        Workbench workbench = workbench();

        String log = logged(() -> assertThrows(IllegalStateException.class,
                () -> workbench.threeWritersThenFail("undone-unit", "undone-repository", "undone-audit")));

        assertEquals(List.of(), all(PREPARED, log), "nothing prepared");
        assertEquals(0, count(pool(), "undone-unit"));
        assertEquals(0, count(pool(), "undone-repository"));
        assertEquals(0, count(audit(), "undone-audit"));
        assertEquals(0, inDoubt(), "no branch left prepared");
    }

    /** Sets the system properties of the pool whose keys start with {@code prefix}: an XA pool on {@code url}. */
    private static void pool(String prefix, String url) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("url", url);
        settings.put("username", postgres.getUsername());
        settings.put("password", postgres.getPassword());
        settings.put("xa", "true");
        settings.forEach((key, value) -> {
            System.setProperty(prefix + key, value);
            SET.add(prefix + key);
        });
    }

    private static DataSource pool() {
        return CDI.current().select(DataSource.class).get();
    }

    private static DataSource audit() {
        return CDI.current().select(DataSource.class, NamedLiteral.of("audit")).get();
    }

    private static Workbench workbench() {
        return CDI.current().select(Workbench.class).get();
    }

    /** What the server logged while {@code action} ran, between a statement before it and one after it. */
    private static String logged(Executable action) throws Throwable {
        String begin = mark();
        action.execute();
        String end = mark();
        // the log follows the server's output: wait until it holds the statement run after the action
        LOGGED.waitUntil(frame -> LOG.toUtf8String().contains(end), 30, TimeUnit.SECONDS);
        String all = LOG.toUtf8String();
        return all.substring(all.indexOf(begin), all.indexOf(end));
    }

    /** Runs a statement the log shows, and returns the text that finds it there. */
    private static String mark() throws SQLException {
        String marker = "mark-" + UUID.randomUUID();
        try (Connection connection = pool().getConnection();
             var statement = connection.prepareStatement("SELECT ?")) {
            statement.setString(1, marker);
            statement.executeQuery().close();
        }
        return marker;
    }

    /** The transaction ids {@code pattern} finds in {@code log}, in order. */
    private static List<String> all(Pattern pattern, String log) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(log);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * The global transaction id of a prepared branch: the PostgreSQL driver names a branch
     * {@code <format id>_<global transaction id>_<branch qualifier>}, both ids in Base64, which holds no {@code _}.
     */
    private static String globalId(String branch) {
        return branch.split("_")[1];
    }

    /** How many rows of {@code store} have the id {@code id}, outside any transaction. */
    private static long count(DataSource store, String id) throws SQLException {
        try (Connection connection = store.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM item WHERE id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /** How many branches the server holds prepared, in any database. */
    private static long inDoubt() throws SQLException {
        try (Connection connection = pool().getConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM pg_prepared_xacts")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /**
     * Why Docker cannot be used here, or {@code null} when it can. {@code isDockerAvailable()} starts Testcontainers'
     * resource reaper on the way and may throw instead of returning {@code false} (see
     * {@code FlywaySchemaMigratorPostgresIT}): whatever it throws means no usable Docker.
     */
    private static String dockerProblem() {
        try {
            return DockerClientFactory.instance().isDockerAvailable() ? null : "no Docker daemon answered";
        } catch (RuntimeException | LinkageError unusable) {
            return unusable.getClass().getSimpleName() + ": " + unusable.getMessage();
        }
    }
}
