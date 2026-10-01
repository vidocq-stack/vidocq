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
package io.vidocq.runtime.extensions.essentials.devconsole;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The query mode's SQL options in the editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the
 * page loads (SQL spec §3, §5): aliases, several targets, quoted identifiers, comments, completion, diagnostics,
 * parameters and formatting, with a language as a Mansart pools panel publishes it for PostgreSQL. JDQL's behaviour,
 * which none of these options touches, is {@link QueryLanguageTest}'s.
 */
class SqlLanguageTest {

    /**
     * The SQL fixture: tasks whose project_id refers to projects, both with an id and a title; "Order", a mixed-case
     * name that is a keyword too, with a column "due date"; sales.orders, a table of another schema.
     */
    private static final String SQL = """
            {"mode": "query",
             "dialect": {
               "keywords": ["SELECT", "FROM", "WHERE", "JOIN", "LEFT", "INNER", "OUTER", "ON", "AS", "AND", "OR",
                            "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "GROUP", "BY", "HAVING", "ORDER", "ASC",
                            "DESC", "LIMIT", "OFFSET", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "WITH",
                            "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "COUNT", "MAX", "TRUE", "FALSE",
                            "INTEGER"],
               "functions": ["COUNT", "MAX", "UPPER", "COALESCE"],
               "clauses": ["SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT",
                           "OFFSET", "SET", "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO"],
               "targetAfter": ["FROM", "JOIN", "UPDATE", "INTO"],
               "aliases": true, "self": null, "quote": "'", "identifierQuote": "\\"", "unquotedCase": "lower"},
             "targets": {
               "tasks": {"detail": "table · public", "attributes": {
                 "id": {"type": "integer", "detail": "int8 · column"},
                 "title": {"type": "string", "detail": "varchar(200) · column"},
                 "price": {"type": "number", "detail": "numeric(10,2) · column"},
                 "due_date": {"type": "string", "format": "date", "detail": "date · column"},
                 "project_id": {"type": "integer", "detail": "int8 · column", "target": "projects"}}},
               "projects": {"detail": "table · public", "attributes": {
                 "id": {"type": "integer", "detail": "int8 · column"},
                 "title": {"type": "string", "detail": "varchar(80) · column"},
                 "lead": {"type": "string", "detail": "varchar(80) · column"}}},
               "Order": {"detail": "table · public", "attributes": {
                 "id": {"type": "integer", "detail": "int4 · column"},
                 "due date": {"type": "string", "format": "date", "detail": "date · column"}}},
               "sales.orders": {"detail": "view · sales", "schema": "sales", "attributes": {
                 "id": {"type": "integer", "detail": "int4 · column"},
                 "total": {"type": "number", "detail": "numeric(38,0) · column"}}}}}
            """;

    private static Context context;
    private static Value json;
    private static Value language;
    private static Value keystroke;
    private static Value sql;

    @BeforeAll
    static void loadTheModule() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        Value exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
        language = exports.getMember("queryLanguage").execute();
        keystroke = exports.getMember("keystroke");
        sql = json.invokeMember("parse", SQL);
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = SqlLanguageTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int number(Value object, String member) {
        return object.getMember(member).asInt();
    }

    /** The tokens of {@code text} read with the SQL fixture, each as kind:text, separated by spaces. */
    private static String tokens(String text) {
        Value tokens = language.invokeMember("tokenize", text, sql);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < tokens.getArraySize(); i++) {
            Value t = tokens.getArrayElement(i);
            out.add(t.getMember("kind").asString() + ":" + text.substring(number(t, "from"), number(t, "to")));
        }
        return String.join(" ", out);
    }

    // ------------------------------------------------------------------------------------------------ tokens, scope

    @Test
    void anAliasNamesItsTableAfterItOrAfterAsAndAKeywordIsNeverOne() {
        assertEquals("keyword:SELECT target:t punct:. attribute:title punct:, target:p punct:. attribute:lead "
                + "keyword:FROM target:tasks target:t keyword:JOIN target:projects keyword:AS target:p keyword:ON "
                + "target:p punct:. attribute:id operator:= target:t punct:. attribute:project_id",
                tokens("SELECT t.title, p.lead FROM tasks t JOIN projects AS p ON p.id = t.project_id"),
                "a path from an alias is a column, where a value goes too");
        assertEquals("keyword:FROM target:tasks keyword:WHERE attribute:id operator:= number:1",
                tokens("FROM tasks WHERE id = 1"), "WHERE is no alias");
        assertEquals("keyword:SELECT target:tasks punct:. attribute:price keyword:FROM target:tasks",
                tokens("SELECT tasks.price FROM tasks"), "a table's own name heads a path too");
    }

    @Test
    void everyTargetAfterFromJoinUpdateOrIntoIsInScopeAndFromTakesAList() {
        assertEquals("keyword:SELECT attribute:lead punct:, attribute:price keyword:FROM target:tasks target:t "
                + "punct:, target:projects target:p", tokens("SELECT lead, price FROM tasks t, projects p"),
                "a bare column of either table");
        assertEquals("keyword:INSERT keyword:INTO target:tasks punct:( attribute:title punct:, attribute:price "
                + "punct:) keyword:VALUES punct:( string:'a' punct:, number:1 punct:)",
                tokens("INSERT INTO tasks (title, price) VALUES ('a', 1)"));
        assertEquals("keyword:UPDATE target:tasks keyword:SET attribute:title operator:= parameter::t",
                tokens("UPDATE tasks SET title = :t"));
        assertEquals("keyword:DELETE keyword:FROM target:tasks keyword:WHERE attribute:id operator:= number:1",
                tokens("DELETE FROM tasks WHERE id = 1"));
    }

    @Test
    void aQuotedIdentifierIsANameItsDoubledQuoteOneLookedUpAsWritten() {
        assertEquals("keyword:SELECT target:o punct:. attribute:\"due date\" punct:, attribute:\"id\" "
                + "keyword:FROM target:\"Order\" target:o",
                tokens("SELECT o.\"due date\", \"id\" FROM \"Order\" o"));
        assertEquals("keyword:SELECT identifier:\"a\"\"b\" keyword:FROM identifier:\"Tasks\"",
                tokens("SELECT \"a\"\"b\" FROM \"Tasks\""), "a quoted name is looked up as written: no Tasks");
        assertEquals("keyword:SELECT identifier:\"select\" keyword:FROM target:tasks",
                tokens("SELECT \"select\" FROM tasks"), "a quoted name is never a keyword");
        assertEquals("keyword:SELECT identifier:\"open", tokens("SELECT \"open"), "it runs to the end of its line");
    }

    @Test
    void anUnquotedNameIsLookedUpAsTheDatabaseStoresIt() {
        assertEquals("keyword:SELECT attribute:TITLE keyword:FROM target:TASKS", tokens("SELECT TITLE FROM TASKS"),
                "PostgreSQL folds an unquoted name to lower case");
        assertEquals("keyword:FROM keyword:Order", tokens("FROM Order"),
                "Order unquoted is the keyword, and the name order: the table is \"Order\"");
    }

    @Test
    void aQualifiedTargetIsLookedUpAsWrittenThenWithoutItsSchema() {
        assertEquals("keyword:SELECT target:s punct:. attribute:total keyword:FROM target:sales punct:. "
                + "target:orders target:s", tokens("SELECT s.total FROM sales.orders s"));
        assertEquals("keyword:FROM target:public punct:. target:tasks keyword:WHERE attribute:title",
                tokens("FROM public.tasks WHERE title"), "public.tasks is no target: tasks is");
        assertEquals("keyword:FROM identifier:other punct:. identifier:nothing keyword:WHERE identifier:x",
                tokens("FROM other.nothing WHERE x"));
    }

    @Test
    void aSubQueryATableExpressionAndAFunctionsFromAreLeftUnchecked() {
        assertEquals("keyword:SELECT attribute:title keyword:FROM target:tasks keyword:WHERE attribute:id keyword:IN "
                + "punct:( keyword:SELECT identifier:nope keyword:FROM identifier:nothing punct:)",
                tokens("SELECT title FROM tasks WHERE id IN (SELECT nope FROM nothing)"));
        assertEquals("keyword:WITH target:recent keyword:AS punct:( keyword:SELECT identifier:id keyword:FROM "
                + "identifier:tasks punct:) keyword:SELECT identifier:x keyword:FROM target:recent",
                tokens("WITH recent AS (SELECT id FROM tasks) SELECT x FROM recent"));
        assertEquals("keyword:SELECT identifier:extract punct:( identifier:year keyword:FROM attribute:due_date "
                + "punct:) keyword:FROM target:tasks",
                tokens("SELECT extract(year FROM due_date) FROM tasks"), "FROM in parentheses names no target");
    }

    @Test
    void aSelectAliasACastsTypeAndAnUnknownFunctionAreNames() {
        assertEquals("keyword:SELECT attribute:title keyword:AS identifier:t2 punct:, attribute:price identifier:p2 "
                + "keyword:FROM target:tasks keyword:ORDER keyword:BY identifier:t2",
                tokens("SELECT title AS t2, price p2 FROM tasks ORDER BY t2"));
        assertEquals("keyword:SELECT attribute:due_date operator::: identifier:text punct:, identifier:date_trunc "
                + "punct:( string:'day' punct:, attribute:due_date punct:) keyword:FROM target:tasks",
                tokens("SELECT due_date::text, date_trunc('day', due_date) FROM tasks"));
    }

    @Test
    void commentsAreTokensOfTheirOwnThatNothingElseSees() {
        assertEquals("keyword:SELECT comment:-- the title attribute:title keyword:FROM comment:/* all */ "
                + "target:tasks", tokens("SELECT -- the title\n title FROM /* all */ tasks"));
        assertEquals("keyword:SELECT number:1 comment:/* open", tokens("SELECT 1 /* open"));
        assertEquals("keyword:SELECT number:1 operator:- operator:- number:2", tokens("SELECT 1 - -2"),
                "a minus sign, then another, is no comment");
    }

    // ------------------------------------------------------------------------------------------------ parameters

    @Test
    void aParameterComparedWithAColumnThroughAnAliasTakesItsType() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"min\":{\"type\":\"number\",\"description\":"
                + "\"compared with t.price (number)\"},\"d\":{\"type\":\"string\",\"format\":\"date\","
                + "\"description\":\"compared with t.due_date (date)\"}},\"required\":[\"min\",\"d\"],"
                + "\"additionalProperties\":false}",
                json.invokeMember("stringify", language.invokeMember("parameters",
                        "SELECT * FROM tasks t WHERE t.price > :min AND :d = t.due_date", sql)).asString());
    }

    // ------------------------------------------------------------------------------------------------ keystrokes

    /**
     * {@code marked} after {@code key} with {@code data}: one | is the caret, two | the ends of the selection; "null"
     * when the module lets the browser type the key.
     */
    private static String press(String marked, String key, Value data) {
        int start = marked.indexOf('|');
        int second = marked.indexOf('|', start + 1);
        String text = marked.replace("|", "");
        int end = second < 0 ? start : second - 1;
        Value edit = keystroke.execute(language, text, start, end, key, data);
        if (edit.isNull()) {
            return "null";
        }
        int from = number(edit, "from");
        String next = text.substring(0, from) + edit.getMember("insert").asString()
                + text.substring(number(edit, "to"));
        int caret = from + number(edit, "caret");
        Value anchor = edit.getMember("anchor");
        if (anchor == null || anchor.isNull()) {
            return next.substring(0, caret) + "|" + next.substring(caret);
        }
        int other = from + anchor.asInt();
        return next.substring(0, Math.min(other, caret)) + "|" + next.substring(Math.min(other, caret),
                Math.max(other, caret)) + "|" + next.substring(Math.max(other, caret));
    }

    @Test
    void theIdentifierQuotePairsAndStepsOverAsTheStringQuoteDoes() {
        assertEquals("[\"()\",\"''\",\"\\\"\\\"\"]",
                json.invokeMember("stringify", language.invokeMember("pairsOf", sql)).asString());
        assertEquals("SELECT \"|\"", press("SELECT |", "\"", sql));
        assertEquals("SELECT \"Order\"|", press("SELECT \"Order|\"", "\"", sql));
        assertEquals("\"|o|\"", press("|o|", "\"", sql));
        assertEquals("\"a\"\"|\"", press("\"a\"|", "\"", sql), "a doubled quote is typed as a pair, inside the name");
        assertEquals("null", press("'it|'", "\"", sql), "inside a string, the browser types it");
        assertEquals("|", press("\"|\"", "Backspace", sql));
        assertEquals("null", press("x = |", "\"", null), "JDQL's quote alone: \" is typed as it is");
    }
}
