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

import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab of actions of each pool (SQL spec §4.1), on H2 through the real Mansart pool and the panel the console
 * reads: what each pool offers, Tables and its replays, Describe, Preview, and the statement Query and Execute take.
 */
class PoolActionsTest {

    private final PoolsLivePanel panel = new PoolsLivePanel();
    private TestPools pools;

    @AfterEach
    void stop() {
        if (pools != null) {
            pools.close();
        }
    }

    private void bootWithTables() {
        pools = TestPools.boot("vidocq.pool.url", TestPools.h2("default"), "vidocq.pool.reports.url",
                TestPools.h2("reports"));
        pools.run("@Default", TestPools.SCHEMA);
    }

    private PanelAction action(String id) {
        return panel.actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    private ActionResult call(String id, String... namesAndValues) {
        Map<String, String> given = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            given.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return action(id).call().apply(given);
    }

    /** The rows of a result's body, the text between "rows": and "more". */
    private static String rows(ActionResult result) {
        String body = result.body();
        return body.substring(body.indexOf("\"rows\":") + 7, body.lastIndexOf(",\"more\":"));
    }

    @Test
    void eachPoolHasATabOfItsActionsTheDefaultOneFirst() {
        bootWithTables();

        List<PanelAction> actions = panel.actions();

        assertEquals(List.of("default.tables", "default.describe", "default.preview", "default.query",
                "default.execute", "reports.tables", "reports.query", "reports.execute"),
                actions.stream().map(PanelAction::id).toList(), "a pool with no table has no Describe, no Preview");
        assertEquals(List.of("@Default", "@Default", "@Default", "@Default", "@Default", "reports", "reports",
                "reports"), actions.stream().map(PanelAction::group).toList());
        assertEquals(List.of("Tables", "Describe", "Preview", "Query", "Execute"),
                actions.subList(0, 5).stream().map(PanelAction::label).toList());
        assertEquals(List.of("open_tasks", "projects", "tasks", "sales.orders"),
                action("default.describe").arguments().getFirst().allowedValues(), "schema then name order");
        assertEquals("Runs this SQL on @Default. A DDL statement (CREATE, ALTER, DROP, TRUNCATE…) may be committed by "
                + "the database itself whatever is chosen.", action("default.execute").confirmation());
        assertNull(action("default.query").confirmation(), "a query runs on the first click");
        assertEquals(List.of("rollback", "commit"), action("default.execute").arguments().get(1).allowedValues());
        String schema = action("default.query").arguments().getFirst().schema();
        assertEquals(schema, action("default.execute").arguments().getFirst().schema());
        assertEquals("{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"contentMediaType\":\"text/x-query\",\"x-language\":\"sql-default\",\"description\":"
                + "\"SELECT … FROM … WHERE … = :name\"},\"params\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"x-parameters-of\":\"sql\",\"description\":\"the named parameters, a JSON object: "
                + "{\\\"name\\\": 3}\"}},\"required\":[\"sql\"]}", schema);
        assertTrue(action("reports.query").arguments().getFirst().schema().contains("\"x-language\":\"sql-reports\""));
    }

    @Test
    void aPoolsKeyIsItsNameMadeAnActionKey() {
        Set<String> used = new HashSet<>();

        assertEquals("default", PoolActions.key("@Default", used));
        assertEquals("audit-log", PoolActions.key("Audit_Log", used));
        assertEquals("p-1st", PoolActions.key("1st", used));
        assertEquals("default-2", PoolActions.key("default", used), "the @Default pool's key is taken");
        assertEquals("a".repeat(30), PoolActions.key("a".repeat(45), used));
        assertEquals("a".repeat(28) + "-2", PoolActions.key("A".repeat(45), used));
    }

    @Test
    void tablesListsTheTablesAndViewsEachWithItsDescribeAndPreview() {
        bootWithTables();

        ActionResult tables = call("default.tables");

        assertFalse(tables.error(), tables.summary());
        assertEquals(ActionResult.ROWS, tables.contentType());
        assertEquals("3 tables, 1 view", tables.summary());
        assertTrue(tables.body().startsWith("{\"columns\":[{\"name\":\"schema\",\"type\":\"\"},{\"name\":\"name\","
                + "\"type\":\"\"},{\"name\":\"kind\",\"type\":\"\"},{\"name\":\"columns\",\"type\":\"\"},"
                + "{\"name\":\"replay\",\"type\":\"\"},{\"name\":\"replay\",\"type\":\"\"}]"), tables.body());
        assertEquals("[[\"public\",\"open_tasks\",\"VIEW\",2,\"default.describe {\\\"table\\\":\\\"open_tasks\\\"}\","
                + "\"default.preview {\\\"table\\\":\\\"open_tasks\\\"}\"],[\"public\",\"projects\",\"BASE TABLE\",2,"
                + "\"default.describe {\\\"table\\\":\\\"projects\\\"}\",\"default.preview {\\\"table\\\":"
                + "\\\"projects\\\"}\"],[\"public\",\"tasks\",\"BASE TABLE\",6,\"default.describe {\\\"table\\\":"
                + "\\\"tasks\\\"}\",\"default.preview {\\\"table\\\":\\\"tasks\\\"}\"],[\"sales\",\"orders\","
                + "\"BASE TABLE\",2,\"default.describe {\\\"table\\\":\\\"sales.orders\\\"}\",\"default.preview "
                + "{\\\"table\\\":\\\"sales.orders\\\"}\"]]", rows(tables));
    }

    @Test
    void aTableCreatedAfterTheBootIsListedWithoutReplays() {
        bootWithTables();
        panel.actions();
        pools.run("@Default", "CREATE TABLE later (id INT)");

        assertTrue(rows(call("default.tables")).contains("[\"public\",\"later\",\"BASE TABLE\",1,\"\",\"\"]"),
                "Describe and Preview offer the tables of the boot only");
    }

    @Test
    void describeGivesEachColumnItsTypeAndKeysAndItsIndexesInTheSummary() {
        bootWithTables();

        ActionResult tasks = call("default.describe", "table", "tasks");

        assertFalse(tasks.error(), tasks.summary());
        assertEquals("[[\"id\",\"BIGINT\",false,null,1,null],[\"title\",\"CHARACTER VARYING(200)\",false,null,null,"
                + "null],[\"price\",\"NUMERIC(10,2)\",true,null,null,null],[\"due\",\"DATE\",true,null,null,null],"
                + "[\"done\",\"BOOLEAN\",false,\"FALSE\",null,null],[\"project_id\",\"BIGINT\",true,null,null,"
                + "\"projects.id\"]]", rows(tasks));
        assertTrue(tasks.summary().startsWith("6 columns · indexes: "), tasks.summary());
        assertTrue(tasks.summary().contains("idx_tasks_title (title)"), tasks.summary());
        assertEquals("[[\"id\",\"INTEGER\",false,null,1,null],[\"Total\",\"NUMERIC(38)\",true,null,null,null]]",
                rows(call("default.describe", "table", "sales.orders")), "a table of another schema");
    }

    @Test
    void previewReadsTheFirstRowsOfATableUpToItsLimit() {
        bootWithTables();

        ActionResult two = call("default.preview", "table", "tasks", "limit", "2");

        assertTrue(two.summary().matches("first 2 rows in \\d+ ms"), two.summary());
        assertTrue(rows(two).startsWith("[[1,\"first\",\"12.50\",\"2026-10-01\",false,1],[2,\"second\""),
                rows(two));
        assertTrue(call("default.preview", "table", "tasks", "limit", "").summary().matches("3 rows in \\d+ ms"),
                "100 rows when the limit is empty");
        assertEquals("[]", rows(call("default.preview", "table", "sales.orders", "limit", "")),
                "quoted as the database quotes it");
        assertFalse(action("default.preview").arguments().get(1).accepts("1001"));
        assertFalse(action("default.preview").arguments().get(1).accepts("0"));
        assertTrue(action("default.preview").arguments().get(1).accepts("1000"));
    }

    @Test
    void queryAndExecuteRunTheStatementTheyAreGiven() {
        bootWithTables();

        assertEquals("[[\"second\"]]", rows(call("default.query", "statement",
                "{\"sql\":\" SELECT title FROM tasks WHERE id = :id \",\"params\":\"{\\\"id\\\": 2}\"}")));
        assertEquals("[[\"first\"]]", rows(call("default.query", "statement",
                "{\"sql\":\"SELECT title FROM tasks WHERE id = :id\",\"params\":{\"id\":1}}")), "params as an object");
        assertEquals("1 row · committed", call("default.execute", "statement",
                "{\"sql\":\"UPDATE tasks SET title = 'changed' WHERE id = 1\"}", "transaction", "commit").summary());
        assertEquals("1 row · rolled back", call("default.execute", "statement",
                "{\"sql\":\"DELETE FROM tasks WHERE id = 1\"}", "transaction", "rollback").summary());
        assertEquals("[[\"changed\"]]", rows(call("default.query", "statement",
                "{\"sql\":\"SELECT title FROM tasks WHERE id = 1\"}")));

        assertEquals("statement: not valid JSON at character 2", call("default.query", "statement", "{x").summary());
        assertEquals("query: unknown argument", call("default.query", "statement", "{\"query\":\"SELECT 1\"}")
                .summary());
        assertEquals("sql: missing", call("default.query", "statement", "{\"sql\":\" \"}").summary());
        assertEquals("params.id: not a value or a list of values", call("default.query", "statement",
                "{\"sql\":\"SELECT :id\",\"params\":{\"id\":{\"a\":1}}}").summary());
    }

    @Test
    void aPoolWhoseTablesCannotBeReadKeepsTablesQueryAndExecuteAndNeverShowsItsUrl() {
        String url = "jdbc:h2:tcp://127.0.0.1:1/nothing;USER=sa";
        pools = TestPools.boot("vidocq.pool.url", url, "vidocq.pool.acquireTimeout", "PT1S");

        assertEquals(List.of("default.tables", "default.query", "default.execute"),
                panel.actions().stream().map(PanelAction::id).toList());
        ActionResult tables = call("default.tables");
        assertTrue(tables.error());
        assertFalse(tables.summary().contains("127.0.0.1:1/nothing") || tables.body().contains("127.0.0.1:1/nothing"),
                tables.summary());
        assertEquals(0, pools.pool("@Default").snapshot().active());
    }

    @Test
    void aDevReloadReadsTheNewBootsPools() {
        bootWithTables();
        assertEquals(8, panel.actions().size());
        pools.close();

        pools = TestPools.boot("vidocq.pool.url", TestPools.h2("next"));
        panel.start(null);

        assertEquals(List.of("default.tables", "default.query", "default.execute"),
                panel.actions().stream().map(PanelAction::id).toList(), "the new boot's empty database");
    }
}
