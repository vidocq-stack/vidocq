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
}
