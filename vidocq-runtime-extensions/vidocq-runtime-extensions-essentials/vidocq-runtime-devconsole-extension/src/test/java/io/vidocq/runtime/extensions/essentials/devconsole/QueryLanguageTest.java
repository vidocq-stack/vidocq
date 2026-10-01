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
 * The query mode of the editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the page loads
 * (query mode spec §3, §5): its tokens and context, completion, diagnostics, parameters, formatting and keystrokes,
 * with the language a Mansart Data panel publishes for JDQL. The JSON language is {@link EditorCoreTest}'s.
 */
class QueryLanguageTest {

    /**
     * The JDQL fixture, as the Mansart Data panel sends its language: a Task whose project refers to a Project, whose
     * lead refers back to a Task, a cycle of references.
     */
    private static final String JDQL = """
            {"mode": "query",
             "dialect": {
               "keywords": ["SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS", "NULL", "BETWEEN",
                            "LIKE", "IN", "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG",
                            "MIN", "MAX", "TRUE", "FALSE"],
               "functions": ["UPPER", "LOWER", "LENGTH", "ABS", "CONCAT", "COUNT", "SUM", "AVG", "MIN", "MAX"],
               "clauses": ["SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM"],
               "targetAfter": ["FROM", "UPDATE"], "self": "this", "quote": "'"},
             "targets": {
               "Task": {"detail": "table task", "attributes": {
                 "id": {"type": "integer", "detail": "Long · id, generated"},
                 "title": {"type": "string", "detail": "String · column title"},
                 "status": {"type": "string", "enum": ["OPEN", "DONE"], "detail": "Status · column status"},
                 "price": {"type": "number", "detail": "BigDecimal · column price"},
                 "dueDate": {"type": "string", "format": "date", "detail": "LocalDate · column due_date"},
                 "project": {"type": "integer", "detail": "→ Project · column project_id", "target": "Project"}}},
               "Project": {"detail": "table project", "attributes": {
                 "id": {"type": "integer", "detail": "Long · id, generated"},
                 "name": {"type": "string", "detail": "String · column name"},
                 "lead": {"type": "integer", "detail": "→ Task · column lead_id", "target": "Task"}}}}}
            """;

    /** One emoji, two UTF-16 units: offsets must count both. */
    private static final String EMOJI = "\uD83D\uDE00";

    private static Context context;
    private static Value json;
    private static Value language;
    private static Value keystroke;
    private static Value jdql;

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
        jdql = parsed(JDQL);
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = QueryLanguageTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code text} parsed by the engine's own JSON.parse, as the page parses a language; null stays null. */
    private static Value parsed(String text) {
        return text == null ? null : json.invokeMember("parse", text);
    }

    private static int number(Value object, String member) {
        return object.getMember(member).asInt();
    }

    /** The tokens of {@code text} read with {@code data}, each as kind:text, separated by spaces. */
    private static String tokens(String text, Value data) {
        Value tokens = language.invokeMember("tokenize", text, data);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < tokens.getArraySize(); i++) {
            Value t = tokens.getArrayElement(i);
            out.add(t.getMember("kind").asString() + ":" + text.substring(number(t, "from"), number(t, "to")));
        }
        return String.join(" ", out);
    }

    private static String tokens(String text) {
        return tokens(text, jdql);
    }

    // ------------------------------------------------------------------------------------------------ tokens

    @Test
    void theLanguageIsAQueryWhosePairsAreParenthesesAndQuotes() {
        assertEquals("query", language.getMember("id").asString());
        assertEquals("[\"()\",\"''\"]", json.invokeMember("stringify", language.getMember("pairs")).asString());
    }

    @Test
    void everyKindOfToken() {
        assertEquals("keyword:SELECT function:UPPER punct:( attribute:title punct:) punct:, string:'it''s' punct:, "
                + "number:12 punct:, number:1.5 punct:, parameter::min punct:, parameter:?1 keyword:FROM target:Task "
                + "keyword:WHERE attribute:project punct:. attribute:name operator:<> string:'x' keyword:AND "
                + "attribute:id operator:>= number:3 operator:- number:2L invalid:12ab invalid:# identifier:nothing",
                tokens("SELECT UPPER(title), 'it''s', 12, 1.5, :min, ?1 FROM Task "
                        + "WHERE project.name <> 'x' AND id >= 3 - 2L 12ab # nothing"));
    }

    @Test
    void keywordsIgnoreCaseAndAFunctionIsOneOfTheDialectsBeforeAParenthesis() {
        assertEquals("keyword:select function:count punct:( keyword:this punct:) keyword:from target:Task "
                + "keyword:where function:upper punct:( attribute:title punct:) keyword:like string:'a%'",
                tokens("select count(this) from Task where upper (title) like 'a%'"));
        assertEquals("keyword:SELECT keyword:COUNT keyword:FROM target:Task", tokens("SELECT COUNT FROM Task"),
                "COUNT with no parenthesis is a keyword");
    }

    @Test
    void theTargetIsTheNameAfterTheFirstFromOrUpdateWhereverTheCaretIs() {
        assertEquals("keyword:SELECT attribute:title punct:, attribute:price keyword:FROM target:Task",
                tokens("SELECT title, price FROM Task"), "the attributes of a target written after them");
        assertEquals("keyword:UPDATE target:Task keyword:SET attribute:title operator:= parameter::t",
                tokens("UPDATE Task SET title = :t"));
        assertEquals("keyword:SELECT identifier:Task keyword:FROM target:Project", tokens("SELECT Task FROM Project"),
                "a target's name elsewhere is no attribute of the target");
    }

    @Test
    void aPathGoesThroughTheReferencesOneStepAtATimeACycleIncluded() {
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:project punct:. attribute:lead punct:. "
                + "attribute:project punct:. attribute:name operator:= keyword:this punct:. attribute:title",
                tokens("FROM Task WHERE project.lead.project.name = this.title"));
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:project punct:. identifier:title",
                tokens("FROM Task WHERE project.title"), "a Project has no title");
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:project punct:. identifier:order",
                tokens("FROM Task WHERE project.order"), "a keyword in a path is a name");
    }

    @Test
    void aStringRunsToItsClosingQuoteADoubledOneInsideOrToTheEndOfItsLine() {
        assertEquals("keyword:WHERE attribute:title operator:= string:'it''s' keyword:AND identifier:x operator:= "
                + "string:'open keyword:FROM target:Task", tokens("WHERE title = 'it''s' AND x = 'open\nFROM Task"));
        assertEquals("string:'''", tokens("'''"), "a doubled quote, then no closing one");
    }

    @Test
    void withNoVocabularyOrAnUnknownOrQualifiedTargetEveryNameIsAnIdentifier() {
        assertEquals("keyword:FROM identifier:Task keyword:WHERE identifier:title operator:= number:1",
                tokens("FROM Task WHERE title = 1", null));
        assertEquals("keyword:FROM identifier:Tsk keyword:WHERE identifier:title", tokens("FROM Tsk WHERE title"));
        assertEquals("keyword:FROM identifier:io punct:. identifier:acme punct:. identifier:Task keyword:WHERE "
                + "identifier:title", tokens("FROM io.acme.Task WHERE title"));
    }

    @Test
    void aKnownTargetMaySpellAKeyword() {
        Value order = parsed("{\"targets\": {\"Order\": {\"attributes\": {\"id\": {\"type\": \"integer\"}}}}}");

        assertEquals("keyword:FROM target:Order keyword:ORDER keyword:BY attribute:id",
                tokens("FROM Order ORDER BY id", order));
    }

    @Test
    void offsetsAreUtf16UnitsWhateverTheLineEndsAndEmoji() {
        String text = "FROM Task\r\nWHERE title = '" + EMOJI + "' " + EMOJI;

        assertEquals("[{\"from\":0,\"to\":4,\"kind\":\"keyword\"},{\"from\":5,\"to\":9,\"kind\":\"target\"},"
                + "{\"from\":11,\"to\":16,\"kind\":\"keyword\"},{\"from\":17,\"to\":22,\"kind\":\"attribute\"},"
                + "{\"from\":23,\"to\":24,\"kind\":\"operator\"},{\"from\":25,\"to\":29,\"kind\":\"string\"},"
                + "{\"from\":30,\"to\":32,\"kind\":\"invalid\"}]",
                json.invokeMember("stringify", language.invokeMember("tokenize", text, jdql)).asString());
    }

    @Test
    void oddDataReadsAsNoVocabularyAndNeverThrows() {
        String text = "FROM Task WHERE p.q = :a AND (x";
        for (String odd : new String[] {null, "\"a string\"", "42", "[]", "{\"targets\": \"x\"}",
                "{\"dialect\": \"x\", \"targets\": {\"Task\": 1}}",
                "{\"dialect\": {\"keywords\": \"x\", \"quote\": \"ab\", \"self\": 3, \"clauses\": [1]}}"}) {
            assertEquals("keyword:FROM identifier:Task keyword:WHERE identifier:p punct:. identifier:q operator:= "
                    + "parameter::a keyword:AND punct:( identifier:x", tokens(text, parsed(odd)), odd);
        }
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:p punct:. identifier:q",
                tokens("FROM Task WHERE p.q", parsed("{\"targets\": {\"Task\": {\"attributes\": "
                        + "{\"p\": {\"target\": \"Nothing\"}}}}}")), "a reference to a target that is none");
        assertEquals("keyword:FROM target:Task keyword:WHERE identifier:p",
                tokens("FROM Task WHERE p", parsed("{\"targets\": {\"Task\": {}}}")), "a target with no attributes");
    }

    // ------------------------------------------------------------------------------------------------ completion

    /** Every keyword of the fixture but the functions and self, in its order: what follows them in a clause. */
    private static final String OTHER_KEYWORDS = "SELECT FROM WHERE ORDER BY AND OR NOT IS NULL BETWEEN LIKE IN ASC "
            + "DESC UPDATE SET DELETE TRUE FALSE";
    /** Every keyword of the fixture, in its order. */
    private static final String KEYWORDS = "SELECT FROM WHERE ORDER BY AND OR NOT IS NULL BETWEEN LIKE IN ASC DESC "
            + "UPDATE SET DELETE COUNT THIS SUM AVG MIN MAX TRUE FALSE";
    /** The functions of the fixture as they are inserted, a | where the caret lands. */
    private static final String FUNCTIONS = "UPPER(|) LOWER(|) LENGTH(|) ABS(|) CONCAT(|) COUNT(|) SUM(|) AVG(|) "
            + "MIN(|) MAX(|)";

    /**
     * The completion where {@code marked} has its |, read with {@code data}: "from-to", then each item's insert, a |
     * where the caret lands inside it, separated by spaces; "null" when there is none.
     */
    private static String complete(String marked, Value data) {
        Value found = completion(marked, data);
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

    private static String complete(String marked) {
        return complete(marked, jdql);
    }

    /** The items of that completion as label: detail, one per line. */
    private static String details(String marked) {
        Value items = completion(marked, jdql).getMember("items");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < items.getArraySize(); i++) {
            Value item = items.getArrayElement(i);
            out.add(item.getMember("label").asString() + ": " + item.getMember("detail").asString());
        }
        return String.join("\n", out);
    }

    private static Value completion(String marked, Value data) {
        int caret = marked.indexOf('|');
        return language.invokeMember("complete", marked.substring(0, caret) + marked.substring(caret + 1), caret,
                data);
    }

    @Test
    void afterFromOrUpdateTheTargetsWithTheirDetail() {
        assertEquals("5-5 Task Project", complete("FROM |"));
        assertEquals("Task: table task\nProject: table project", details("FROM |"));
        assertEquals("5-6 Task", complete("FROM T|"));
        assertEquals("12-16 Task", complete("DELETE FROM ta|sk WHERE id = 1"), "the whole word, ignoring case");
        assertEquals("7-7 Task Project", complete("UPDATE |"));
    }

    @Test
    void afterANameAndADotTheAttributesOfTheTargetItRefersTo() {
        assertEquals("24-24 id name lead", complete("FROM Task WHERE project.|"));
        assertEquals("id: Long · id, generated\nname: String · column name\nlead: → Task · column lead_id",
                details("FROM Task WHERE project.|"));
        assertEquals("24-26 name", complete("FROM Task WHERE project.na|"), "the last segment only");
        assertEquals("29-29 id title status price dueDate project", complete("FROM Task WHERE project.lead.|"),
                "back to the Task: a cycle is one step at a time");
        assertEquals("21-21 id title status price dueDate project", complete("FROM Task WHERE this.|"));
        assertEquals("null", complete("FROM Task WHERE title.|"), "a title is no reference");
        assertEquals("null", complete("FROM Task WHERE nothing.|"));
    }

    @Test
    void inAnExpressionClauseOfAKnownTargetItsAttributesSelfTheFunctionsThenTheOtherKeywords() {
        assertEquals("7-7 id title status price dueDate project this " + FUNCTIONS + " " + OTHER_KEYWORDS,
                complete("SELECT | FROM Task"), "the target written after the caret");
        assertEquals("id: Long · id, generated\ntitle: String · column title\nstatus: Status · column status\n"
                + "price: BigDecimal · column price\ndueDate: LocalDate · column due_date\n"
                + "project: → Project · column project_id\nthis: the Task itself",
                String.join("\n", details("SELECT | FROM Task").lines().limit(7).toList()));
        assertEquals("16-18 title", complete("FROM Task WHERE ti|"));
        assertEquals("16-18 UPPER(|) UPDATE", complete("FROM Task WHERE up|"));
        assertEquals("16-18 UPPER UPDATE", complete("FROM Task WHERE up|(title) = 'A'"), "a parenthesis already there");
        assertEquals("16-19 ORDER", complete("FROM Task WHERE ord|"));
        assertEquals("26-26 id title status price dueDate project this " + FUNCTIONS + " " + OTHER_KEYWORDS,
                complete("FROM Task ORDER BY title, |"));
        assertEquals("16-18 status", complete("UPDATE Task SET st| = 'DONE'"));
    }

    @Test
    void anywhereElseOrWithNoKnownTargetTheKeywordsInCapitals() {
        assertEquals("0-0 " + KEYWORDS, complete("|"));
        assertEquals("10-10 " + KEYWORDS, complete("FROM Task |"), "after the target");
        assertEquals("10-12 WHERE", complete("FROM Task wh|"), "filtered ignoring case");
        assertEquals("7-7 " + KEYWORDS, complete("SELECT |"), "no target yet");
        assertEquals("16-16 " + KEYWORDS, complete("FROM Nope WHERE |"), "an unknown target");
        assertEquals("16-18 UPDATE", complete("FROM Task WHERE up|", null), "no vocabulary");
    }

    @Test
    void nothingInAStringANumberOrAParameter() {
        assertEquals("null", complete("FROM Task WHERE title = 'a|'"));
        assertEquals("null", complete("FROM Task WHERE title = 'open|"));
        assertEquals("null", complete("FROM Task WHERE id = 1|"));
        assertEquals("null", complete("FROM Task WHERE id = :i|"));
        assertEquals("null", complete("FROM |", null), "a target position with no target to offer");
    }

    @Test
    void oddDataOffersTheKeywordsAndNeverThrows() {
        for (String odd : new String[] {"\"a string\"", "[]", "{\"targets\": {\"Task\": {}}}",
                "{\"targets\": {\"Task\": {\"attributes\": {\"p\": {\"target\": \"Nothing\"}}}}}"}) {
            assertEquals("16-18 UPDATE", complete("FROM Task WHERE up|", parsed(odd)).replace("UPPER(|) ", ""), odd);
            assertEquals("null", complete("FROM Task WHERE p.|", parsed(odd)), odd);
        }
    }

    @Test
    void aLargeVocabularyIsReadOnceAndCompletesByPrefix() {
        StringBuilder targets = new StringBuilder("{\"targets\": {");
        for (int t = 0; t < 500; t++) {
            targets.append(t == 0 ? "" : ",").append("\"T").append(t).append("\": {\"attributes\": {");
            for (int a = 0; a < 30; a++) {
                targets.append(a == 0 ? "" : ",").append("\"a").append(a).append("\": {\"type\": \"string\"}");
            }
            targets.append("}}");
        }
        Value large = parsed(targets.append("}}").toString());

        assertEquals("5-8 T49 T490 T491 T492 T493 T494 T495 T496 T497 T498 T499", complete("FROM T49|", large));
        large.getMember("targets").putMember("T4999", parsed("{\"attributes\": {}}"));
        assertEquals("5-9 T499", complete("FROM T499|", large), "read once: a later change of the object is not seen");
    }

    // ------------------------------------------------------------------------------------------------ diagnostics

    /** The diagnostics of {@code text} read with {@code data}, one per line: severity from-to message. */
    private static String diagnose(String text, Value data) {
        Value found = language.invokeMember("diagnose", text, data);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < found.getArraySize(); i++) {
            Value d = found.getArrayElement(i);
            out.add(d.getMember("severity").asString() + " " + number(d, "from") + "-" + number(d, "to") + " "
                    + d.getMember("message").asString());
        }
        return String.join("\n", out);
    }

    private static String diagnose(String text) {
        return diagnose(text, jdql);
    }

    @Test
    void anUnknownTargetIsAnError() {
        assertEquals("error 5-8 unknown target Tsk", diagnose("FROM Tsk WHERE titel = 1"),
                "and no attribute of it checked");
        assertEquals("error 7-10 unknown target Tsk", diagnose("UPDATE Tsk SET title = 1"));
    }

    @Test
    void anUnknownAttributeOfTheTargetOrOfTheTargetOfAReferenceIsAnError() {
        assertEquals("error 16-21 unknown attribute titel of Task", diagnose("FROM Task WHERE titel = 1"));
        assertEquals("error 7-12 unknown attribute titel of Task", diagnose("SELECT titel FROM Task"),
                "the target written after it");
        assertEquals("error 24-27 unknown attribute nme of Project", diagnose("FROM Task WHERE project.nme = 'a'"));
        assertEquals("error 29-34 unknown attribute titel of Task",
                diagnose("FROM Task WHERE project.lead.titel = 'a'"), "back through the cycle");
    }

    @Test
    void aPathThroughAnAttributeThatIsNoReferenceIsAnError() {
        assertEquals("error 22-23 title is not a reference", diagnose("FROM Task WHERE title.x = 1"));
        assertEquals("error 21-22 title is not a reference", diagnose("FROM Task WHERE title."),
                "the dot itself when nothing follows it");
        assertEquals("", diagnose("FROM Task WHERE project."), "a reference being typed");
    }

    @Test
    void anUnterminatedStringAndAnUnbalancedParenthesisAreErrors() {
        assertEquals("error 24-29 unterminated string", diagnose("FROM Task WHERE title = 'open\nORDER BY id"));
        assertEquals("", diagnose("FROM Task WHERE title = 'it''s'"), "a doubled quote is inside the string");
        assertEquals("error 16-17 '(' never closed", diagnose("FROM Task WHERE (id = 1 OR (id = 2)"));
        assertEquals("error 22-23 no '(' to close", diagnose("FROM Task WHERE id = 1)"));
    }

    @Test
    void withNoVocabularyNoKnownTargetOrAQualifiedOneNoNameIsChecked() {
        assertEquals("", diagnose("FROM Tsk WHERE titel.x = 1", null));
        assertEquals("", diagnose("SELECT titel WHERE x = 1"), "no target");
        assertEquals("", diagnose("FROM io.acme.Task WHERE titel = 1"), "a qualified name, never checked");
        assertEquals("error 24-29 unterminated string", diagnose("FROM Task WHERE title = 'open", null),
                "a string is checked without a vocabulary");
    }

    @Test
    void aQueryBeingTypedIsCheckedForItsNamesOnlyAndNeverThrows() {
        assertEquals("", diagnose("FROM "));
        assertEquals("", diagnose("FROM Task WHERE "));
        assertEquals("", diagnose("SELECT COUNT(this) FROM Task WHERE id BETWEEN 1 AND :max ORDER BY"),
                "the grammar is the server's to judge");
        assertEquals("error 16-17 '(' never closed\nerror 17-19 unknown attribute ti of Task",
                diagnose("FROM Task WHERE (ti"));
        for (String typed : new String[] {"", "F", "FROM T", "FROM Task WHERE project.", "FROM Task WHERE '",
                "FROM Task WHERE ((", "FROM Task WHERE project..name", ".", "FROM .", "SELECT this. FROM Task"}) {
            for (int caret = 0; caret <= typed.length(); caret++) {
                completion(typed.substring(0, caret) + "|" + typed.substring(caret), jdql);
            }
            diagnose(typed);
        }
    }

    @Test
    void oddDataChecksLessAndNeverThrows() {
        Value nothing = parsed("{\"targets\": {\"Task\": {\"attributes\": {\"p\": {\"target\": \"Nothing\"}}}}}");

        assertEquals("", diagnose("FROM Task WHERE p.q.r = 1", nothing), "a reference to a target that is none");
        assertEquals("", diagnose("FROM Task WHERE zz = 1", parsed("{\"targets\": {\"Task\": {}}}")),
                "a target that lists no attribute");
        assertEquals("error 5-9 unknown target Nope", diagnose("FROM Nope", nothing));
    }

    // ------------------------------------------------------------------------------------------------ parameters

    /** The JSON Schema of the params of {@code text} read with {@code data}, as JSON. */
    private static String parameters(String text, Value data) {
        return json.invokeMember("stringify", language.invokeMember("parameters", text, data)).asString();
    }

    private static String parameters(String text) {
        return parameters(text, jdql);
    }

    /** The whole schema of {@code properties} (a JSON object's members), their names all required, in that order. */
    private static String schema(String properties, String... required) {
        return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":["
                + String.join(",", List.of(required).stream().map(name -> "\"" + name + "\"").toList())
                + "],\"additionalProperties\":false}";
    }

    @Test
    void aComparedParameterTakesTheTypeOfItsAttribute() {
        assertEquals(schema("\"s\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"],"
                + "\"description\":\"compared with status (string)\"}", "s"),
                parameters("FROM Task WHERE status = :s"));
        assertEquals(schema("\"min\":{\"type\":\"number\",\"description\":\"compared with price (number)\"}", "min"),
                parameters("FROM Task WHERE :min <= price"), "the parameter first");
        assertEquals(schema("\"d\":{\"type\":\"string\",\"format\":\"date\","
                + "\"description\":\"compared with dueDate (date)\"}", "d"),
                parameters("FROM Task WHERE dueDate <> :d"));
        assertEquals(schema("\"n\":{\"type\":\"string\",\"description\":\"compared with project.name (string)\"}",
                "n"), parameters("FROM Task WHERE project.name = :n"), "through a reference");
        assertEquals(schema("\"s\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"],"
                + "\"description\":\"compared with status (string)\"}", "s"),
                parameters("SELECT title FROM Task WHERE status != :s"), "the target after the parameter's clause");
    }

    @Test
    void likeBetweenAndInHaveSchemasOfTheirOwn() {
        assertEquals(schema("\"p\":{\"type\":\"string\",\"description\":\"pattern for title\"}", "p"),
                parameters("FROM Task WHERE title NOT LIKE :p"));
        assertEquals(schema("\"lo\":{\"type\":\"number\",\"description\":\"lower bound of price (number)\"},"
                + "\"hi\":{\"type\":\"number\",\"description\":\"upper bound of price (number)\"}", "lo", "hi"),
                parameters("FROM Task WHERE price BETWEEN :lo AND :hi"));
        assertEquals(schema("\"hi\":{\"type\":\"number\",\"description\":\"upper bound of price (number)\"}", "hi"),
                parameters("FROM Task WHERE price NOT BETWEEN 3 AND :hi"));
        assertEquals(schema("\"ss\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"]},"
                + "\"description\":\"list of status values\"}", "ss"),
                parameters("FROM Task WHERE status NOT IN :ss"));
    }

    @Test
    void aSetParameterIsTheNewValueOfItsAttributeAndAnyOtherUseTakesAnyValue() {
        assertEquals(schema("\"t\":{\"type\":\"string\",\"description\":\"new value of title\"},\"f\":{},"
                + "\"id\":{\"type\":\"integer\",\"description\":\"compared with id (integer)\"}", "t", "f", "id"),
                parameters("UPDATE Task SET title = :t, price = price * :f WHERE id = :id"));
        assertEquals(schema("\"a\":{},\"b\":{}", "a", "b"), parameters("FROM Task WHERE UPPER(title) = :a OR :b"));
    }

    @Test
    void aParameterUsedTwiceKeepsItsFirstTypedUseAndAPositionalOneHasNoKey() {
        assertEquals(schema("\"x\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}", "x"),
                parameters("FROM Task WHERE :x IS NULL OR title = :x OR price = :x"));
        assertEquals(schema("\"t\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}", "t"),
                parameters("FROM Task WHERE id = ?1 AND title = :t"));
        assertEquals(schema(""), parameters("FROM Task WHERE id = ?1"));
    }

    @Test
    void withNoVocabularyEveryParameterTakesAnyValueAndIsRequired() {
        assertEquals(schema("\"t\":{},\"n\":{}", "t", "n"), parameters("FROM Task WHERE title = :t AND n = :n", null));
    }

    @Test
    void aParameterNamedProtoIsAPropertyLikeAnyOther() {
        assertEquals(schema("\"__proto__\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}",
                "__proto__"), parameters("FROM Task WHERE title = :__proto__"));
    }

    // ------------------------------------------------------------------------------------------------ formatting

    private static String format(String text) {
        return language.invokeMember("format", text, jdql).asString();
    }

    @Test
    void formatStartsEachClauseOnALineOfItsOwnAndAndOrOnAnIndentedOne() {
        assertEquals("""
                SELECT title, price
                FROM Task
                WHERE status = :s
                  AND price BETWEEN 1 AND :max
                  OR NOT project.name LIKE 'a%'
                ORDER BY title DESC""", format("select title , price from Task where status=:s and price between 1 "
                + "and :max or not project.name like 'a%' order by title desc"));
        assertEquals("DELETE FROM Task\nWHERE id = ?1", format("delete   from Task where id = ?1"));
        assertEquals("UPDATE Task\nSET title = 'x', price = price * 2\nWHERE (id = 1\n  OR id = 2)",
                format("update Task set title='x',price=price*2 where (id=1 or id=2)"));
    }

    @Test
    void formatWritesKeywordsAndFunctionsInCapitalsSelfAsTheDialectDoesAndTheRestAsWritten() {
        assertEquals("SELECT COUNT(this)\nFROM Task\nWHERE UPPER(title) = 'It''s'\n  AND price > -1.50\n  AND id IN "
                + "(:a, :b)\n  AND dueDate = :Due", format("select count ( THIS ) from Task where upper(title)='It''s' "
                + "and price>-1.50 and id in(:a,:b) and dueDate=:Due"));
    }

    @Test
    void formattingAFormattedQueryChangesNothing() {
        String once = format("select title from Task where title = '" + EMOJI + "' and (id = 1 or id = 2)");

        assertEquals(once, format(once));
    }

    @Test
    void aQueryWithAnUnterminatedStringIsNotFormattedAndSaysWhere() {
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> format("FROM Task\nWHERE title = 'a"));

        assertTrue(thrown.isGuestException(), "an Error thrown by the module");
        assertEquals("Error: line 2: unterminated string", thrown.getMessage());
    }

    // ------------------------------------------------------------------------------------------------ keystrokes

    /**
     * {@code marked} after {@code key} in the query language: one | is the caret, two | the ends of the selection;
     * "null" when the module lets the browser type the key.
     */
    private static String press(String marked, String key) {
        int start = marked.indexOf('|');
        int second = marked.indexOf('|', start + 1);
        String text = marked.replace("|", "");
        int end = second < 0 ? start : second - 1;
        Value edit = keystroke.execute(language, text, start, end, key);
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
    void aQuoteOpensAPairWrapsASelectionAndStepsOverItsClosingQuote() {
        assertEquals("title = '|'", press("title = |", "'"));
        assertEquals("'|abc|'", press("|abc|", "'"));
        assertEquals("'abc'|", press("'abc|'", "'"));
        assertEquals("null", press("it|", "'"), "after a letter: typed as it is");
        assertEquals("'it''|'", press("'it'|", "'"), "a doubled quote is typed as a pair, inside the string");
    }

    @Test
    void aParenthesisPairsOutsideAStringOnlyAndBackspaceDeletesAnEmptyPair() {
        assertEquals("UPPER(|)", press("UPPER|", "("));
        assertEquals("null", press("'a|b'", "("), "a parenthesis in a string is text");
        assertEquals("|", press("'|'", "Backspace"));
        assertEquals("|", press("(|)", "Backspace"));
        assertEquals("null", press("'it''|'", "Backspace"), "a doubled quote then the closing one");
    }
}
