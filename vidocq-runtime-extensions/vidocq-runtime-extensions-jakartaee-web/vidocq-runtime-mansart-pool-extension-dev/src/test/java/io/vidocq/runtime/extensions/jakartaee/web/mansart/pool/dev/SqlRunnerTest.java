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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL run on a pool (SQL spec §4.2), on H2 through the real Mansart pool: what Query and Execute answer and refuse,
 * how their parameters are bound, their transactions, their timeout, and what an SQLException shows.
 */
class SqlRunnerTest {

    private final TestPools pools = TestPools.withTables();
    private final MansartDataSource pool = pools.pool("@Default");
    private final SqlRunner runner = runner(SqlRunner.TIMEOUT_SECONDS);

    @AfterEach
    void stop() {
        pools.close();
    }

    private SqlRunner runner(int timeoutSeconds) {
        return new SqlRunner(pool, '"', timeoutSeconds, pool.config().jdbcUrl(), pool.config().username(),
                pool.config().password());
    }

    private static Map<String, Object> params(Object... namesAndValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            params.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return params;
    }

    /** The rows of a result's body, the text between "rows": and "more". */
    private static String rows(ActionResult result) {
        String body = result.body();
        return body.substring(body.indexOf("\"rows\":") + 7, body.lastIndexOf(",\"more\":"));
    }

    private String title(long id) {
        return rows(runner.query("SELECT title FROM tasks WHERE id = " + id, Map.of()));
    }

    @Test
    void aQueryAnswersItsRowsAsATable() {
        ActionResult result = runner.query("SELECT id, title, price, due FROM tasks ORDER BY id", Map.of());

        assertFalse(result.error(), result.summary());
        assertEquals(ActionResult.ROWS, result.contentType());
        assertTrue(result.summary().matches("3 rows in \\d+ ms"), result.summary());
        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\",\"type\":"
                + "\"CHARACTER VARYING\"},{\"name\":\"price\",\"type\":\"NUMERIC\"},{\"name\":\"due\",\"type\":"
                + "\"DATE\"}],\"rows\":[[1,\"first\",\"12.50\",\"2026-10-01\"],[2,\"second\",null,null],"
                + "[3,\"third\",\"3.00\",\"2026-10-03\"]],\"more\":false}", result.body());
        assertEquals("{\"sql\":\"SELECT id, title, price, due FROM tasks ORDER BY id\",\"params\":{},"
                + "\"transaction\":\"read-only\"}", result.details());
    }

    @Test
    void aQueryShowsAHundredRowsTheNextOneSayingThereAreMore() {
        ActionResult result = runner.query("SELECT * FROM SYSTEM_RANGE(1, 150)", Map.of());

        assertTrue(result.summary().matches("first 100 rows in \\d+ ms"), result.summary());
        assertTrue(result.body().endsWith("[99],[100]],\"more\":true}"), result.body());
    }

    @Test
    void aQueryRefusesAWriteAndASecondStatementBeforeAnythingRuns() {
        for (String write : new String[] {"DELETE FROM tasks", "/* SELECT */ -- SELECT\n DELETE FROM tasks",
                "UPDATE tasks SET title = 'x'"}) {
            ActionResult refused = runner.query(write, Map.of());
            assertTrue(refused.error(), write);
            assertEquals("Query only reads: use Execute", refused.summary(), write);
            assertNull(refused.body());
        }
        assertEquals("one statement at a time", runner.query("SELECT 1; DELETE FROM tasks", Map.of()).summary());
        assertEquals("one statement at a time",
                runner.execute("UPDATE tasks SET title = 'x'; DELETE FROM tasks", Map.of(), true).summary());
        assertEquals("[[\";\"]]", rows(runner.query("SELECT ';' AS semicolon;", Map.of())), "a last ; is fine");
        assertEquals("[[3]]", rows(runner.query("SELECT COUNT(*) FROM tasks", Map.of())), "nothing ran");
    }

    @Test
    void aDollarQuotedStringHidesNoSecondStatementFromQuery() {
        ActionResult refused = runner.query(
                "SELECT $$'$$ AS x; DELETE FROM tasks WHERE id = 1; COMMIT; SELECT $$'$$", Map.of());

        assertTrue(refused.error(), refused.summary());
        assertEquals("one statement at a time", refused.summary());
        assertEquals("[[3]]", rows(runner.query("SELECT COUNT(*) FROM tasks", Map.of())), "nothing ran");
        assertEquals("[[\"it's\",\"second\"]]", rows(runner.query("SELECT $$it's$$, title FROM tasks WHERE id = :id",
                params("id", new BigDecimal("2")))), "a parameter after a dollar-quoted string is bound");
    }

    @Test
    void aNamedParameterIsBoundWhereItIsAndAMissingOneIsRefused() {
        assertEquals("[[\"second\"]]", rows(runner.query("SELECT title FROM tasks WHERE id = :id "
                + "AND title <> ':id' AND \"title\" IS NOT NULL -- :nope", params("id", new BigDecimal("2")))));
        assertEquals("[[\"first\"],[\"third\"]]", rows(runner.query("SELECT title FROM tasks WHERE id = ANY(:ids) "
                + "ORDER BY id", params("ids", List.of(new BigDecimal("1"), new BigDecimal("3"))))), "an array");
        assertEquals("[[\"second\"]]", rows(runner.query("SELECT title FROM tasks WHERE price IS NOT DISTINCT FROM :p "
                + "AND done = :done", params("p", null, "done", false))), "null and a boolean");

        ActionResult missing = runner.query("SELECT title FROM tasks WHERE id = :id OR title = :title",
                params("id", new BigDecimal("2")));
        assertTrue(missing.error());
        assertEquals("missing parameter title", missing.summary());
    }

    @Test
    void anExecuteIsRolledBackUnlessItIsCommittedAsReadingTheTableAgainShows() {
        ActionResult rolledBack = runner.execute("UPDATE tasks SET title = :t WHERE id = 1", params("t", "changed"),
                false);
        assertEquals("1 row · rolled back", rolledBack.summary());
        assertNull(rolledBack.body(), "a count, no rows");
        assertTrue(rolledBack.details().endsWith("\"transaction\":\"rollback\"}"), rolledBack.details());
        assertEquals("[[\"first\"]]", title(1));

        assertEquals("1 row · committed",
                runner.execute("UPDATE tasks SET title = :t WHERE id = 1", params("t", "changed"), true).summary());
        assertEquals("[[\"changed\"]]", title(1));
        assertEquals("no row · committed", runner.execute("DELETE FROM tasks WHERE id = 99", Map.of(), true)
                .summary());

        ActionResult rows = runner.execute("SELECT COUNT(*) AS n FROM tasks", Map.of(), false);
        assertEquals(ActionResult.ROWS, rows.contentType(), "a statement that answers rows");
        assertTrue(rows.summary().matches("1 row in \\d+ ms · rolled back"), rows.summary());
    }

    @Test
    void theConnectionGoesBackToThePoolAsItWasWhateverTheCallDid() throws SQLException {
        runner.query("SELECT * FROM tasks", Map.of());
        runner.query("SELECT nope FROM tasks", Map.of());
        runner.execute("UPDATE tasks SET title = 'x'", Map.of(), false);
        runner.execute("INSERT INTO tasks (id, title) VALUES (1, 'duplicate')", Map.of(), true);

        assertEquals(0, pool.snapshot().active(), "every connection given back");
        try (Connection connection = pool.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertFalse(connection.isReadOnly());
        }
        assertEquals("[[\"first\"]]", title(1));
    }

    @Test
    void aStatementPastItsTimeoutIsCancelledAndSaysSo() {
        long start = System.nanoTime();

        ActionResult result = runner(1).query("SELECT COUNT(*) FROM SYSTEM_RANGE(1, 100000000000) "
                + "WHERE MOD(\"X\", 7) = 8", Map.of());

        assertTrue(result.error());
        assertEquals("the statement ran past 1 s and was cancelled", result.summary());
        assertTrue((System.nanoTime() - start) / 1_000_000_000 < 20, "it ended within its timeout");
        assertEquals(0, pool.snapshot().active());
    }

    @Test
    void anSqlExceptionIsTheDatabasesMessageWithItsStateAndCodeNeverTheUrlTheUserOrThePassword() {
        ActionResult unknown = runner.query("SELECT nope FROM tasks", Map.of());

        assertTrue(unknown.error());
        assertTrue(unknown.summary().contains("Column \"nope\" not found"), unknown.summary());
        assertEquals(ActionResult.TEXT, unknown.contentType());
        assertTrue(unknown.details().endsWith("\"sqlState\":\"42S22\",\"vendorCode\":42122}"), unknown.details());

        String url = "jdbc:h2:tcp://db.example/tasks;USER=tasks";
        DataSource failing = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    throw new SQLException("cannot open " + url + ": password authentication failed for user "
                            + "\"tasks\" with hunter22", "28P01", 0);
                });
        ActionResult refused = new SqlRunner(failing, '"', 30, url, "tasks", "hunter22").query("SELECT 1", Map.of());

        assertEquals("cannot open ***: password authentication failed for user \"***\" with ***", refused.summary());
        assertFalse(refused.body().contains("hunter22") || refused.body().contains("db.example"), refused.body());
        assertFalse(refused.details().contains("hunter22"), refused.details());
    }

    @Test
    void aValueJsonCannotHoldAsSuchIsWrittenAsText() {
        String sql = "SELECT CAST('12345678901234567890123456789012345678' AS NUMERIC(38)) AS big, "
                + "CAST(9007199254740993 AS BIGINT) AS long, X'0102' AS bin, "
                + "CAST(REPEAT('ab', 50) AS BINARY VARYING(100)) AS longer, "
                + "TIMESTAMP WITH TIME ZONE '2026-10-01 12:00:00+02:00' AS zoned, "
                + "TIMESTAMP '2026-10-01 12:00:30' AS local, ARRAY[1, 2] AS list, TRUE AS yes, NULL AS nothing, "
                + "CAST(1.5 AS DOUBLE PRECISION) AS ratio";

        String row = rows(runner.query(sql, Map.of()));

        List<String> values = Arrays.asList(row.substring(2, row.length() - 2).split(","));
        assertEquals("\"12345678901234567890123456789012345678\"", values.get(0), "a NUMERIC(38), whole");
        assertEquals("\"9007199254740993\"", values.get(1), "a long past 2^53");
        assertEquals("\"0x0102\"", values.get(2));
        assertTrue(values.get(3).matches("\"0x(6162){32}…\""), values.get(3));
        assertEquals("\"2026-10-01T12:00+02:00\"", values.get(4), "with its offset");
        assertEquals("\"2026-10-01T12:00:30\"", values.get(5));
        assertEquals("\"[1", values.get(6));
        assertEquals("2]\"", values.get(7), "an array as the JSON of its elements");
        assertEquals("true", values.get(8));
        assertEquals("null", values.get(9));
        assertEquals("1.5", values.get(10));
    }
}
