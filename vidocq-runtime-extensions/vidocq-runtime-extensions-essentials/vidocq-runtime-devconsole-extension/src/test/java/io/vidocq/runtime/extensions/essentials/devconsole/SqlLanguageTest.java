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
import org.graalvm.polyglot.PolyglotException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ------------------------------------------------------------------------------------------------ completion

    /** The functions of the fixture as they are inserted, a | where the caret lands. */
    private static final String FUNCTIONS = "COUNT(|) MAX(|) UPPER(|) COALESCE(|)";
    /** Every keyword of the fixture but its functions, in its order: what follows them in a clause. */
    private static final String OTHER_KEYWORDS = "SELECT FROM WHERE JOIN LEFT INNER OUTER ON AS AND OR NOT NULL IS "
            + "IN LIKE BETWEEN GROUP BY HAVING ORDER ASC DESC LIMIT OFFSET INSERT INTO VALUES UPDATE SET DELETE WITH "
            + "DISTINCT CASE WHEN THEN ELSE END TRUE FALSE INTEGER";

    private static Value completion(String marked) {
        int caret = marked.indexOf('|');
        return language.invokeMember("complete", marked.substring(0, caret) + marked.substring(caret + 1), caret,
                sql);
    }

    /** "from-to", then each item's insert, a | where the caret lands inside it; "null" when there is none. */
    private static String complete(String marked) {
        Value found = completion(marked);
        if (found.isNull()) {
            return "null";
        }
        List<String> out = new ArrayList<>(List.of(number(found, "from") + "-" + number(found, "to")));
        Value items = found.getMember("items");
        for (long i = 0; i < items.getArraySize(); i++) {
            Value item = items.getArrayElement(i);
            String insert = item.getMember("insert").asString();
            Value caret = item.getMember("caret");
            out.add(caret == null || caret.isNull() ? insert
                    : insert.substring(0, caret.asInt()) + "|" + insert.substring(caret.asInt()));
        }
        return String.join(" ", out);
    }

    /** The first {@code count} items of that completion as label: detail, one per line. */
    private static String details(String marked, int count) {
        Value items = completion(marked).getMember("items");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < Math.min(count, items.getArraySize()); i++) {
            Value item = items.getArrayElement(i);
            out.add(item.getMember("label").asString() + ": " + item.getMember("detail").asString());
        }
        return String.join("\n", out);
    }

    @Test
    void whereATargetGoesTheTargetsWrittenAsTheDatabaseNeedsThem() {
        assertEquals("14-14 tasks projects \"Order\" sales.orders", complete("SELECT * FROM |"));
        assertEquals("tasks: table · public\nprojects: table · public\nOrder: table · public\n"
                + "sales.orders: view · sales", details("SELECT * FROM |", 4));
        assertEquals("27-29 projects", complete("SELECT * FROM tasks t JOIN pr|"));
        assertEquals("23-23 tasks projects \"Order\" sales.orders", complete("SELECT * FROM tasks t, |"),
                "after a comma of the FROM list");
        assertEquals("14-18 \"Order\"", complete("SELECT * FROM \"Or|\""), "a quoted name being typed, whole");
        assertEquals("20-20 orders", complete("SELECT * FROM sales.|"), "after a schema, its tables");
    }

    @Test
    void afterAnAliasOrATableAndADotThatTablesColumns() {
        assertEquals("9-9 id title price due_date project_id", complete("SELECT t.| FROM tasks t"),
                "the table is written after the caret");
        assertEquals("id: int8 · column\ntitle: varchar(200) · column", details("SELECT t.| FROM tasks t", 2));
        assertEquals("9-9 id \"due date\"", complete("SELECT o.| FROM \"Order\" o"), "a name with a space, quoted");
        assertEquals("13-15 title", complete("SELECT tasks.ti| FROM tasks"));
        assertEquals("null", complete("SELECT x.| FROM tasks t"), "no x in scope");
    }

    @Test
    void inAnOnTheAliasesTheTargetsThenEveryColumnWithItsTableThenFunctionsAndKeywords() {
        assertEquals("41-41 t p tasks projects id title price due_date project_id id title lead " + FUNCTIONS + " "
                + OTHER_KEYWORDS, complete("SELECT * FROM tasks t JOIN projects p ON |"));
        assertEquals("t: alias of tasks\np: alias of projects\ntasks: table · public\nprojects: table · public\n"
                + "id: int8 · column · tasks\ntitle: varchar(200) · column · tasks",
                details("SELECT * FROM tasks t JOIN projects p ON |", 6));
        assertEquals("7-7 tasks id title price due_date project_id " + FUNCTIONS + " " + OTHER_KEYWORDS,
                complete("SELECT | FROM tasks"), "no self in a dialect that has none");
        assertEquals("30-32 \"due date\"", complete("SELECT * FROM \"Order\" o WHERE du|"));
        assertEquals("7-9 tasks id title price due_date project_id", complete("SELECT \"|\" FROM tasks"),
                "in quotes, the names only");
        assertEquals("null", complete("SELECT * FROM tasks -- ti|"), "nothing in a comment");
    }

    @Test
    void aQueryBeingTypedCompletesAtEveryCaretAndNeverThrows() {
        for (String typed : new String[] {"", "S", "SELECT * FROM ", "SELECT t. FROM tasks t", "SELECT \"",
                "SELECT * FROM tasks t JOIN projects p ON p.", "FROM .", "FROM tasks t, ", "SELECT /* x",
                "WITH r AS (SELECT", "SELECT a::", "SELECT * FROM (SELECT * FROM tasks) x WHERE x."}) {
            for (int caret = 0; caret <= typed.length(); caret++) {
                completion(typed.substring(0, caret) + "|" + typed.substring(caret));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ diagnostics

    /** The diagnostics of {@code text} read with the SQL fixture, one per line: severity from-to message. */
    private static String diagnose(String text) {
        Value found = language.invokeMember("diagnose", text, sql);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < found.getArraySize(); i++) {
            Value d = found.getArrayElement(i);
            out.add(d.getMember("severity").asString() + " " + number(d, "from") + "-" + number(d, "to") + " "
                    + d.getMember("message").asString());
        }
        return String.join("\n", out);
    }

    @Test
    void anUnknownTargetAliasOrColumnIsAnError() {
        assertEquals("error 14-18 unknown target taks", diagnose("SELECT * FROM taks"));
        assertEquals("error 9-14 unknown column titel of tasks", diagnose("SELECT t.titel FROM tasks t"));
        assertEquals("error 7-11 unknown column nope of tasks t or projects p",
                diagnose("SELECT nope FROM tasks t JOIN projects p ON p.id = t.project_id"));
        assertEquals("error 7-8 unknown table or alias x", diagnose("SELECT x.title FROM tasks t"),
                "and nothing after it checked");
        assertEquals("error 15-16 title is not a reference", diagnose("SELECT t.title.x FROM tasks t"));
    }

    @Test
    void aColumnThatTwoTargetsHaveNamedAloneIsAWarning() {
        assertEquals("warning 7-12 title is in tasks t and projects p",
                diagnose("SELECT title FROM tasks t JOIN projects p ON p.id = t.project_id"));
        assertEquals("", diagnose("SELECT t.title, lead FROM tasks t JOIN projects p ON p.id = t.project_id"),
                "named with its alias, or a column of one table only");
    }

    @Test
    void aQualifiedTargetIsCheckedOnlyWhenItIsFound() {
        assertEquals("error 9-13 unknown column nope of sales.orders", diagnose("SELECT s.nope FROM sales.orders s"));
        assertEquals("", diagnose("SELECT title FROM public.tasks"), "found without its schema");
        assertEquals("", diagnose("SELECT nope FROM other.nothing"), "never checked, nor its columns");
    }

    @Test
    void whatTheEditorCannotKnowIsNeverAnError() {
        assertEquals("", diagnose("SELECT * FROM tasks WHERE id IN (SELECT nope FROM nothing)"), "a sub-query");
        assertEquals("", diagnose("WITH recent AS (SELECT id FROM tasks) SELECT x FROM recent"),
                "a common table expression");
        assertEquals("", diagnose("SELECT title AS t2, price p2 FROM tasks ORDER BY t2, p2"),
                "the select list's aliases");
        assertEquals("", diagnose("SELECT due_date::text, date_trunc('day', due_date) FROM tasks"),
                "a cast's type, an unknown function");
        assertEquals("", diagnose("SELECT * FROM tasks WHERE title = OPEN"), "a name where a value goes, a literal");
        assertEquals("", diagnose("SELECT COUNT(*) FROM tasks t, projects p WHERE t.project_id = p.id"));
    }

    @Test
    void aDerivedTableAFunctionsRowsACteWithColumnsAndExtractAreNeverAnError() {
        assertEquals("", diagnose("SELECT x.a FROM (SELECT 1 AS a) x"), "a derived table");
        assertEquals("", diagnose("SELECT s.n, t.title FROM tasks t JOIN (SELECT COUNT(*) AS n FROM tasks) AS s ON "
                + "TRUE WHERE t.id = s.n"), "a derived table joined");
        assertEquals("", diagnose("SELECT g, l.x FROM generate_series(1, 3) g, LATERAL (SELECT g AS x) l"),
                "a function's rows and a lateral sub-query");
        assertEquals("", diagnose("SELECT r.a, b FROM unnest(ARRAY[1]) AS r(a), tasks AS b"), "with its columns");
        assertEquals("", diagnose("WITH r(a) AS (SELECT 1) SELECT r.a FROM r"), "a common table expression's columns");
        assertEquals("", diagnose("SELECT EXTRACT(EPOCH FROM due_date) FROM tasks"), "a field of EXTRACT");
        assertEquals("error 7-11 unknown table or alias nope", diagnose("SELECT nope.a FROM (SELECT 1 AS a) x"),
                "an alias still has to be one");
        assertEquals("error 7-12 unknown column titel of tasks", diagnose("SELECT titel FROM tasks"),
                "a bare column of known targets still checked");
    }

    @Test
    void aDerivedTableIsCompletedByItsAliasNeverAsATable() {
        String items = complete("SELECT * FROM (SELECT 1 AS a) x, tasks t WHERE |");

        assertTrue(items.startsWith("47-47 x t tasks id title "), "its alias, the table's alias and name: " + items);
    }

    @Test
    void anUnterminatedQuotedNameOrCommentIsAnError() {
        assertEquals("error 7-13 unterminated identifier", diagnose("SELECT \"Total\nFROM tasks"));
        assertEquals("error 9-14 unterminated comment", diagnose("SELECT 1 /* x\n"));
        assertEquals("", diagnose("SELECT 1 -- it's a \"comment (\nFROM tasks"), "a comment holds no token");
    }

    @Test
    void aQueryBeingTypedIsCheckedForItsNamesOnlyAndNeverThrows() {
        assertEquals("", diagnose("SELECT * FROM "));
        assertEquals("", diagnose("SELECT t. FROM tasks t"), "a column being typed");
        for (String typed : new String[] {"", "S", "SELECT \"", "FROM .", "FROM tasks t, ", "SELECT /* x",
                "WITH r AS (SELECT", "SELECT a::", "SELECT * FROM tasks t JOIN projects p ON p.", "UPDATE \"",
                "INSERT INTO tasks (", "SELECT * FROM (SELECT * FROM tasks) x WHERE x."}) {
            diagnose(typed);
        }
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

    // ------------------------------------------------------------------------------------------------ formatting

    private static String format(String text) {
        return language.invokeMember("format", text, sql).asString();
    }

    @Test
    void formatPutsEachClauseOnALineOfItsOwnAJoinWithItsOn() {
        assertEquals("""
                SELECT t.title, p.lead
                FROM tasks t
                LEFT JOIN projects p ON p.id = t.project_id
                  AND p.lead LIKE 'a%'
                JOIN "Order" o ON o.id = t.id
                WHERE t.price > :min
                GROUP BY t.title, p.lead
                HAVING COUNT(*) > 1
                ORDER BY t.title
                LIMIT 10
                OFFSET 20""", format("select t.title, p.lead from tasks t left join projects p on p.id = t.project_id "
                + "and p.lead like 'a%' join \"Order\" o on o.id = t.id where t.price > :min group by t.title, p.lead "
                + "having count(*) > 1 order by t.title limit 10 offset 20"));
        assertEquals("INSERT INTO tasks (title, price)\nVALUES ('a', 1.50)",
                format("insert into tasks(title,price) values('a',1.50)"));
    }

    @Test
    void formatCopiesQuotedNamesCastsAndCommentsAndLeavesASubQueryOnItsLine() {
        assertEquals("""
                SELECT "due date"::text -- the date
                FROM "Order" o /* every one */
                WHERE o.id IN (SELECT id FROM tasks WHERE price > 1)""",
                format("select \"due date\" :: text -- the date\nfrom \"Order\" o /* every one */ where o.id in "
                        + "(select id from tasks where price>1)"));
        assertEquals("SELECT 1 -- one\n, 2", format("select 1 -- one\n, 2"), "after a -- comment, a new line");
    }

    @Test
    void formattingAFormattedSqlQueryChangesNothing() {
        String once = format("select t.title from tasks t inner join projects p on p.id = t.project_id where "
                + "t.title = 'a' or t.price between 1 and 2");

        assertEquals("SELECT t.title\nFROM tasks t\nINNER JOIN projects p ON p.id = t.project_id\n"
                + "WHERE t.title = 'a'\n  OR t.price BETWEEN 1 AND 2", once);
        assertEquals(once, format(once));
    }

    @Test
    void anUnterminatedQuotedNameOrCommentIsNotFormattedAndSaysWhere() {
        PolyglotException name = assertThrows(PolyglotException.class, () -> format("SELECT 1\nFROM \"tasks"));
        assertEquals("Error: line 2: unterminated identifier", name.getMessage());
        PolyglotException comment = assertThrows(PolyglotException.class, () -> format("SELECT 1 /* x"));
        assertEquals("Error: line 1: unterminated comment", comment.getMessage());
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
