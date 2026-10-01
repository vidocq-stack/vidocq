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

import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code sql-<pool>} language of each pool (SQL spec §4.3), read at boot from H2's {@code DatabaseMetaData}
 * through the real Mansart pool: its targets, their columns and types, a foreign key as a reference, the product's
 * keywords, the identifier quote, and its size.
 */
class PoolLanguageTest {

    private final PoolsLivePanel panel = new PoolsLivePanel();
    private TestPools pools;

    @AfterEach
    void stop() {
        if (pools != null) {
            pools.close();
        }
    }

    private Map<?, ?> language(String id) {
        PanelLanguage language = panel.languages().stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no language " + id));
        return (Map<?, ?>) Json.parse(language.json());
    }

    private static Map<?, ?> member(Map<?, ?> object, String... path) {
        Map<?, ?> at = object;
        for (String name : path) {
            at = (Map<?, ?>) at.get(name);
        }
        return at;
    }

    @Test
    void eachPoolWhoseTablesWereReadHasItsLanguage() {
        pools = TestPools.boot("vidocq.pool.url", TestPools.h2("default"), "vidocq.pool.reports.url",
                TestPools.h2("reports"));
        pools.run("@Default", TestPools.SCHEMA);

        assertEquals(List.of("sql-default", "sql-reports"), panel.languages().stream().map(PanelLanguage::id).toList(),
                "an empty database has a language too: its keywords");
        assertEquals(Map.of(), member(language("sql-reports"), "targets"));
    }

    @Test
    void itsTargetsAreTheTablesWithTheirColumnsTypesAndReferences() {
        pools = TestPools.withTables();

        Map<?, ?> targets = member(language("sql-default"), "targets");

        assertEquals(List.of("open_tasks", "projects", "tasks", "sales.orders"), List.copyOf(targets.keySet()),
                "the current schema's bare, another's with its schema");
        assertEquals("view · public", member(targets, "open_tasks").get("detail"));
        assertEquals("table · public", member(targets, "tasks").get("detail"));
        assertNull(member(targets, "tasks").get("schema"));
        assertEquals("sales", member(targets, "sales.orders").get("schema"));
        Map<?, ?> tasks = member(targets, "tasks", "attributes");
        assertEquals(List.of("id", "title", "price", "due", "done", "project_id"), List.copyOf(tasks.keySet()));
        assertEquals(Map.of("type", "integer", "detail", "BIGINT · column"), member(tasks, "id"));
        assertEquals(Map.of("type", "string", "detail", "CHARACTER VARYING(200) · column"), member(tasks, "title"));
        assertEquals(Map.of("type", "number", "detail", "NUMERIC(10,2) · column"), member(tasks, "price"));
        assertEquals(Map.of("type", "string", "format", "date", "detail", "DATE · column"), member(tasks, "due"));
        assertEquals(Map.of("type", "boolean", "detail", "BOOLEAN · column"), member(tasks, "done"));
        assertEquals(Map.of("type", "integer", "detail", "BIGINT · column", "target", "projects"),
                member(tasks, "project_id"), "a single-column foreign key refers to its table");
        assertEquals(Map.of("type", "number", "detail", "NUMERIC(38) · column"),
                member(targets, "sales.orders", "attributes", "Total"), "a name as the database stores it");
    }

    @Test
    void itsDialectIsSqlsWithTheProductsKeywordsAndTheDatabasesQuote() {
        pools = TestPools.withTables();

        Map<?, ?> dialect = member(language("sql-default"), "dialect");

        List<?> keywords = (List<?>) dialect.get("keywords");
        assertTrue(keywords.containsAll(List.of("SELECT", "JOIN", "LIMIT", "NULLS")), "SQL-92's, and SQL:2003's");
        assertTrue(keywords.contains("QUALIFY"), "one of H2's own: " + keywords);
        assertEquals(keywords.size(), keywords.stream().distinct().count(), "each once");
        assertEquals(SqlLanguage.FUNCTIONS, dialect.get("functions"));
        assertEquals(List.of("SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT",
                "OFFSET", "SET", "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO"), dialect.get("clauses"));
        assertEquals(List.of("FROM", "JOIN", "UPDATE", "INTO"), dialect.get("targetAfter"));
        assertEquals(Boolean.TRUE, dialect.get("aliases"));
        assertTrue(dialect.containsKey("self") && dialect.get("self") == null, "no self");
        assertEquals("'", dialect.get("quote"));
        assertEquals("\"", dialect.get("identifierQuote"));
        assertEquals("lower", dialect.get("unquotedCase"), "H2 told to store names as PostgreSQL does");
    }

    @Test
    void aLanguagePastItsLimitIsWrittenWithoutDetailsThenWithoutTypesThenNotAtAll() {
        pools = TestPools.withTables();
        PoolMetadata metadata = PoolActions.of(MansartPoolsLive.pools()).getFirst().metadata();
        String full = SqlLanguage.json("@Default", metadata);

        String lean = SqlLanguage.json("@Default", metadata, full.length() - 1);
        assertFalse(lean.contains("\"detail\""), lean);
        assertTrue(lean.contains("\"type\":\"integer\""), "the types stay while they fit");
        String leaner = SqlLanguage.json("@Default", metadata, lean.length() - 1);
        assertFalse(leaner.contains("\"type\":\"integer\"") || leaner.contains("\"format\""), leaner);
        assertTrue(leaner.contains("\"project_id\":{\"target\":\"projects\"}"), "every column and reference stays");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SqlLanguage.json("@Default", metadata, 100));
        assertTrue(refused.getMessage().startsWith("the SQL language of 4 tables is "), refused.getMessage());
    }

    @Test
    void aPoolWhoseTablesCannotBeReadHasNoLanguage() {
        pools = TestPools.boot("vidocq.pool.url", "jdbc:h2:tcp://127.0.0.1:1/nothing", "vidocq.pool.acquireTimeout",
                "PT1S");

        assertEquals(List.of(), panel.languages());
    }
}
