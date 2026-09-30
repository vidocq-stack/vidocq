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
 * The editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the page loads (spec §7): the JSON
 * language's tokens, diagnostics, completion and formatting, and the smart keystrokes. The engine runs interpreted: the
 * tests need no compiler, and the warning that says so is off.
 */
class EditorCoreTest {

    /** The schema most tests check against, as a panel sends one: a Task, with every keyword the editor reads. */
    private static final String TASK = """
            {"type": "object", "required": ["title", "status"],
             "properties": {
               "id": {"type": "integer", "readOnly": true, "description": "column id"},
               "title": {"type": "string", "maxLength": 5, "description": "column title"},
               "status": {"type": "string", "enum": ["OPEN", "DONE"]},
               "due": {"type": "string", "format": "date"},
               "at": {"type": "string", "format": "time"},
               "when": {"type": "string", "format": "date-time"},
               "ref": {"type": "string", "format": "uuid"},
               "points": {"type": ["integer", "null"]},
               "done": {"type": "boolean"},
               "tags": {"type": "array", "items": {"type": "string"}},
               "pair": {"type": "array", "items": [{"type": "integer"}, {"type": "string"}]},
               "owner": {"$ref": "#/$defs/owner"},
               "extra": {"$ref": "other.json#/x"},
               "any": {"anyOf": [{"type": "string"}]}},
             "$defs": {"owner": {"type": "object", "required": ["name"],
               "properties": {"name": {"type": "string"}, "age": {"type": "integer"}},
               "additionalProperties": false}}}
            """;

    /** One emoji, two UTF-16 units: offsets must count both. */
    private static final String EMOJI = "\uD83D\uDE00";

    private static Context context;
    private static Value json;
    private static Value language;

    @BeforeAll
    static void loadTheModule() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        Value exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
        language = exports.getMember("jsonLanguage");
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = EditorCoreTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code text} parsed by the engine's own JSON.parse, as the page parses a schema; null stays null. */
    private static Value parsed(String text) {
        return text == null ? null : json.invokeMember("parse", text);
    }

    private static int number(Value object, String member) {
        return object.getMember(member).asInt();
    }

    /** The tokens of {@code text}, each as kind:text, separated by spaces. */
    private static String tokens(String text) {
        Value tokens = language.invokeMember("tokenize", text);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < tokens.getArraySize(); i++) {
            Value t = tokens.getArrayElement(i);
            out.add(t.getMember("kind").asString() + ":" + text.substring(number(t, "from"), number(t, "to")));
        }
        return String.join(" ", out);
    }

    // ------------------------------------------------------------------------------------------------ tokens

    @Test
    void everyKindOfToken() {
        assertEquals("punct:{ key:\"a\" punct:: punct:[ number:1 punct:, number:-2.5e3 punct:, literal:true punct:, "
                + "literal:null punct:, string:\"x\" punct:] punct:, key:\"b\" punct:: literal:false punct:}",
                tokens("{\"a\": [1, -2.5e3, true, null, \"x\"], \"b\": false}"));
    }

    @Test
    void anUnterminatedStringRunsToTheEndOfItsLineAndIsAString() {
        assertEquals("punct:{ key:\"a\" punct:: string:\"open punct:, number:1 punct:}",
                tokens("{\"a\": \"open\n, 1}"));
    }

    @Test
    void anInvalidRunEndsAtTheNextBlankPunctuationOrQuote() {
        assertEquals("punct:[ invalid:tru punct:, invalid:01 punct:, invalid:'x' punct:, invalid:1. punct:, "
                + "invalid:abc string:\"d\" punct:]", tokens("[tru, 01, 'x', 1., abc\"d\"]"));
        assertEquals("punct:{ key:\"a\" punct:: number:1 invalid:// invalid:note punct:}",
                tokens("{\"a\": 1 // note\n}"), "a comment-like // is no comment in JSON");
    }

    @Test
    void offsetsAreUtf16UnitsWhateverTheLineEndsTabsAndEmoji() {
        String text = "{\r\n\t\"" + EMOJI + "\": \"\u00e9" + EMOJI + "\",\r\n\t\"b\": 1" + EMOJI + "\r\n}";

        assertEquals("[{\"from\":0,\"to\":1,\"kind\":\"punct\"},{\"from\":4,\"to\":8,\"kind\":\"key\"},"
                + "{\"from\":8,\"to\":9,\"kind\":\"punct\"},{\"from\":10,\"to\":15,\"kind\":\"string\"},"
                + "{\"from\":15,\"to\":16,\"kind\":\"punct\"},{\"from\":19,\"to\":22,\"kind\":\"key\"},"
                + "{\"from\":22,\"to\":23,\"kind\":\"punct\"},{\"from\":24,\"to\":27,\"kind\":\"invalid\"},"
                + "{\"from\":29,\"to\":30,\"kind\":\"punct\"}]",
                json.invokeMember("stringify", language.invokeMember("tokenize", text)).asString());
    }

    @Test
    void theLanguageDeclaresItsIdAndPairs() {
        assertEquals("json", language.getMember("id").asString());
        assertEquals("[\"{}\",\"[]\",\"()\",\"\\\"\\\"\"]",
                json.invokeMember("stringify", language.getMember("pairs")).asString());
    }

    // ------------------------------------------------------------------------------------------------ diagnostics

    /** The diagnostics of {@code text} under {@code schema}, one per line: severity from-to message. */
    private static String diagnose(String text, String schema) {
        Value found = language.invokeMember("diagnose", text, parsed(schema));
        List<String> out = new ArrayList<>();
        for (long i = 0; i < found.getArraySize(); i++) {
            Value d = found.getArrayElement(i);
            out.add(d.getMember("severity").asString() + " " + number(d, "from") + "-" + number(d, "to") + " "
                    + d.getMember("message").asString());
        }
        return String.join("\n", out);
    }

    @Test
    void theFirstSyntaxErrorOnlyAtItsOffsets() {
        assertEquals("error 6-11 unterminated string", diagnose("{\"a\": \"open", null));
        assertEquals("error 6-10 invalid string", diagnose("{\"a\": \"\\x\"}", null));
        assertEquals("error 8-11 expected ',' or '}'", diagnose("{\"a\": 1 \"b\": 2}", null));
        assertEquals("error 3-4 expected ',' or ']'", diagnose("[1 2]", null));
        assertEquals("error 7-8 trailing comma", diagnose("{\"a\": 1,}", null));
        assertEquals("error 2-3 trailing comma", diagnose("[1,]", null));
        assertEquals("error 9-10 nothing after the value", diagnose("{\"a\": 1} x", null));
        assertEquals("error 5-6 expected ':'", diagnose("{\"a\" 1}", null));
        assertEquals("error 1-2 expected a key or '}'", diagnose("{1: 2}", null));
        assertEquals("error 9-10 expected a key", diagnose("{\"a\": 1, 2}", null));
        assertEquals("error 6-7 expected a value", diagnose("{\"a\": }", null));
        assertEquals("error 6-9 unexpected token", diagnose("{\"a\": tru}", null));
        assertEquals("error 8-10 expected ',' or '}'", diagnose("{\"a\": 1 // note\n}", null));
    }

    @Test
    void anErrorAtTheEndOfTheTextIsEmptyAtItsLength() {
        assertEquals("error 7-7 expected ',' or '}'", diagnose("{\"a\": 1", null));
        assertEquals("error 0-0 expected a value", diagnose("", null));
        assertEquals("error 3-3 expected a value", diagnose(" \r\n", null));
    }

    @Test
    void aTextThatParsesHasNoDiagnosticWithoutASchema() {
        assertEquals("", diagnose("{\"a\": [1, {\"b\": null}], \"c\": \"\\u00e9\", \"d\": -0.5e-3}", null));
    }

    @Test
    void syntaxOffsetsCountCrlfTabsAndEmojiAsTheTextareaDoes() {
        assertEquals("error 15-16 trailing comma",
                diagnose("{\r\n\t\"" + EMOJI + "\": \"\u00e9" + EMOJI + "\",\r\n}", null));
    }

    @Test
    void aDeeplyNestedTextNeverExhaustsTheStack() {
        assertEquals("", diagnose("[".repeat(10_000) + "]".repeat(10_000), null));
        assertEquals("error 10000-10000 expected a value", diagnose("[".repeat(10_000), null));
    }

    @Test
    void aMissingRequiredKeyIsAnErrorOnItsObjectsOpeningBrace() {
        assertEquals("error 0-1 missing required key \"status\"", diagnose("{\"title\": \"a\"}", TASK));
    }

    @Test
    void aValueOfTheWrongTypeIsAnErrorOnTheValue() {
        assertEquals("error 10-11 expected string\nerror 41-44 expected integer or null",
                diagnose("{\"title\": 1, \"status\": \"OPEN\", \"points\": 1.5}", TASK));
        assertEquals("error 56-60 expected boolean",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"points\": 2.0, \"done\": null}", TASK),
                "2.0 is an integer, null is one of the types listed");
    }

    @Test
    void aValueOutsideItsEnumIsAnError() {
        assertEquals("error 25-31 not one of \"OPEN\", \"DONE\"",
                diagnose("{\"title\": \"a\", \"status\": \"LATE\"}", TASK));
    }

    @Test
    void aStringLongerThanMaxLengthIsAnErrorCountingCharactersNotUnits() {
        assertEquals("error 10-18 longer than 5 characters",
                diagnose("{\"title\": \"abcdef\", \"status\": \"OPEN\"}", TASK));
        assertEquals("", diagnose("{\"title\": \"" + EMOJI.repeat(5) + "\", \"status\": \"OPEN\"}", TASK));
    }

    @Test
    void aKeyTheSchemaDoesNotListIsAWarningOnTheKey() {
        assertEquals("warning 33-41 unknown key \"colour\"",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"colour\": 1}", TASK));
        assertEquals("", diagnose("{\"b\": 1}",
                "{\"type\": \"object\", \"properties\": {\"a\": {}}, \"additionalProperties\": true}"));
        assertEquals("error 6-9 expected integer", diagnose("{\"b\": \"x\"}", "{\"type\": \"object\", "
                + "\"properties\": {\"a\": {}}, \"additionalProperties\": {\"type\": \"integer\"}}"));
        assertEquals("", diagnose("{\"b\": 1}",
                "{\"type\": \"object\", \"properties\": {\"a\": {}}, \"patternProperties\": {\"^b\": {}}}"));
    }

    @Test
    void aStringThatDoesNotLookLikeItsFormatIsAWarning() {
        assertEquals("warning 40-52 not a date, such as 2026-09-30\n"
                + "warning 77-95 not a date-time, such as 2026-09-30T14:30:00\n"
                + "warning 104-109 not a uuid, such as 123e4567-e89b-12d3-a456-426614174000",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"due\": \"30/09/2026\", \"at\": \"14:30\", "
                        + "\"when\": \"2026-09-30 14:30\", \"ref\": \"123\"}", TASK));
    }

    @Test
    void theChecksFollowItemsTheirArrayFormAndALocalRefAtDepth() {
        assertEquals("error 47-48 expected string\nerror 63-64 expected string\n"
                + "error 82-83 missing required key \"name\"\nwarning 83-89 unknown key \"nick\"",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"tags\": [\"x\", 2], \"pair\": [1, 2, true], "
                        + "\"owner\": {\"nick\": \"y\"}}", TASK));
    }

    @Test
    void aSelfReferencingSchemaIsCheckedAtEveryDepth() {
        assertEquals("error 34-37 expected integer", diagnose("{\"children\": [{\"children\": [{\"n\": \"x\"}]}]}",
                "{\"$ref\": \"#/$defs/node\", \"$defs\": {\"node\": {\"type\": \"object\", \"properties\": {"
                        + "\"children\": {\"type\": \"array\", \"items\": {\"$ref\": \"#/$defs/node\"}}, "
                        + "\"n\": {\"type\": \"integer\"}}}}}"));
    }

    @Test
    void anotherRefIsNotFollowedAndNothingUnderAnyOfIsChecked() {
        assertEquals("", diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"extra\": 5, \"any\": 5}", TASK));
    }

    @Test
    void anOddSchemaNeverThrowsItChecksLess() {
        assertEquals("", diagnose("{\"x\": 1}", "{\"$ref\": \"#/$defs/a\", \"$defs\": {"
                + "\"a\": {\"$ref\": \"#/$defs/b\"}, \"b\": {\"$ref\": \"#/$defs/a\"}}}"), "a $ref cycle");
        assertEquals("", diagnose("{\"x\": 1}", "{\"type\": \"object\", \"properties\": 5, \"required\": \"x\"}"));
        assertEquals("", diagnose("\"abcdef\"", "{\"type\": 7, \"enum\": \"x\", \"maxLength\": \"3\", \"format\": 5}"));
        assertEquals("", diagnose("{\"x\": 1}", "\"not a schema\""));
        assertEquals("", diagnose("{\"a\": 1}", "{\"properties\": {\"a\": {\"type\": \"strng\"}}}"),
                "a type the editor does not know");
        assertEquals("error 0-1 missing required key \"b\"",
                diagnose("{\"a\": 1}", "{\"properties\": {\"a\": {}}, \"required\": [\"b\"]}"),
                "a required key that properties does not list is still required");
    }

    @Test
    void aKeyNamedProtoIsAKeyLikeAnyOther() {
        String schema = "{\"type\": \"object\", \"required\": [\"__proto__\"], "
                + "\"properties\": {\"__proto__\": {\"type\": \"integer\"}}}";

        assertEquals("error 14-17 expected integer", diagnose("{\"__proto__\": \"x\"}", schema));
        assertEquals("error 0-1 missing required key \"__proto__\"", diagnose("{}", schema));
        assertEquals("", diagnose("{\"__proto__\": 1}", schema));
    }

    // ------------------------------------------------------------------------------------------------ completion

    /**
     * The completion where {@code marked} has its |: "from-to", then each item's insert on a line of its own, a |
     * where the caret lands inside it; "null" when there is none.
     */
    private static String complete(String marked, String schema) {
        Value found = completion(marked, schema);
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
        return String.join("\n", out);
    }

    /** The items of that completion as label: detail, one per line. */
    private static String details(String marked, String schema) {
        Value items = completion(marked, schema).getMember("items");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < items.getArraySize(); i++) {
            Value item = items.getArrayElement(i);
            out.add(item.getMember("label").asString() + ": " + item.getMember("detail").asString());
        }
        return String.join("\n", out);
    }

    private static Value completion(String marked, String schema) {
        int caret = marked.indexOf('|');
        return language.invokeMember("complete", marked.substring(0, caret) + marked.substring(caret + 1), caret,
                parsed(schema));
    }

    @Test
    void aKeyPositionOffersTheMissingKeysRequiredFirstReadOnlyLast() {
        assertEquals("""
                15-15
                "title": "|"
                "status": "OPEN"
                "due": "|"
                "at": "|"
                "when": "|"
                "ref": "|"
                "points": 0
                "tags": [|]
                "pair": [|]
                "owner": {"name": "|"}
                "extra": null
                "any": null
                "id": 0""", complete("{\"done\": true, |}", TASK));
    }

    @Test
    void eachKeyItemSaysItsTypeWhetherItIsRequiredOrGeneratedAndItsDescription() {
        assertEquals("""
                title: string, required — column title
                status: enum, required
                due: string
                at: string
                when: string
                ref: string
                points: integer | null
                done: boolean
                tags: array
                pair: array
                owner: object
                extra: any
                any: any
                id: integer, generated — column id""", details("{|}", TASK));
    }

    @Test
    void theKeysAfterTheCaretAreWrittenToo() {
        assertEquals("1-1\n\"status\": \"OPEN\"", complete("{|\n  \"title\": \"a\", \"due\": \"\", \"at\": \"\", "
                + "\"when\": \"\", \"ref\": \"\", \"points\": 1, \"done\": true, \"tags\": [], \"pair\": [], "
                + "\"owner\": {}, \"extra\": 1, \"any\": 1, \"id\": 1\n}", TASK));
    }

    @Test
    void aPartlyTypedKeyIsReplacedWithItsQuotesIgnoringCase() {
        assertEquals("1-5\n\"title\": \"|\"", complete("{\"ti|\"}", TASK));
        assertEquals("1-5\n\"title\": \"|\"", complete("{\"TI|\"}", TASK));
        assertEquals("1-3\n\"title\": \"|\"", complete("{ti|}", TASK), "typed without its quotes");
        assertEquals("1-5\n\"title\"", complete("{\"ti|\": \"x\"}", TASK), "a key already followed by ':' alone");
    }

    @Test
    void aKeyWithNoClosingQuoteIsReplacedUpToTheCaretOnly() {
        assertEquals("10-13\n\"due\": \"|\"", complete("{\"id\": 1, \"du|\n\"title\": \"a\"}", TASK));
    }

    @Test
    void theKeysOfANestedObjectComeFromItsSchema() {
        assertEquals("11-11\n\"name\": \"|\"\n\"age\": 0", complete("{\"owner\": {|}}", TASK));
    }

    @Test
    void aValuePositionOffersTheEnumBooleansNullAndContainers() {
        assertEquals("11-11\n\"OPEN\"\n\"DONE\"", complete("{\"status\": |}", TASK));
        assertEquals("9-9\ntrue\nfalse", complete("{\"done\": |}", TASK));
        assertEquals("11-11\nnull", complete("{\"points\": |}", TASK));
        assertEquals("10-10\n{\"name\": \"|\"}", complete("{\"owner\": |}", TASK));
        assertEquals("9-9\n[|]", complete("{\"tags\": |}", TASK));
        assertEquals("11-14\n\"DONE\"", complete("{\"status\": \"d|\"}", TASK), "filtered by what is typed");
        assertEquals("9-10\ntrue", complete("{\"done\": t|}", TASK));
    }

    @Test
    void theRootValueStartsWithTheRequiredKeys() {
        assertEquals("0-0\n{\"title\": \"|\", \"status\": \"OPEN\"}", complete("|", TASK));
    }

    @Test
    void nothingElsewhereNorWithoutASchemaForThePlace() {
        assertEquals("null", complete("{\"title\": \"a\"|}", TASK), "after a value");
        assertEquals("null", complete("{\"title\": \"a|\"}", TASK), "a string that is no enum");
        assertEquals("null", complete("{\"tags\": [|]}", TASK), "an item that is a string");
        assertEquals("null", complete("{\"colour\": |}", TASK), "a key the schema does not list");
        assertEquals("null", complete("{|}", null), "no schema");
        assertEquals("null", complete("{\"a\": 1}|", TASK), "after the root value");
    }

    @Test
    void completionOffsetsSurviveEmojiAndCrlf() {
        assertEquals("10-14\n\"title\": \"|\"", complete("{\"" + EMOJI + "\": 1, \"ti|\"}", TASK));
        assertEquals("5-9\n\"title\": \"|\"", complete("{\r\n  \"ti|\"\r\n}", TASK));
    }

    @Test
    void aKeyNamedProtoIsOfferedLikeAnyOther() {
        assertEquals("1-1\n\"__proto__\": 0",
                complete("{|}", "{\"type\": \"object\", \"properties\": {\"__proto__\": {\"type\": \"integer\"}}}"));
    }

    // ------------------------------------------------------------------------------------------------ formatting

    private static String format(String text) {
        return language.invokeMember("format", text).asString();
    }

    @Test
    void formatIndentsTwoSpacesOneMemberPerLine() {
        assertEquals("""
                {
                  "a": [
                    1,
                    {
                      "b": null
                    }
                  ],
                  "c": true
                }""", format("{\"a\":[1,{\"b\":null}],\"c\":true}"));
    }

    @Test
    void anEmptyObjectOrArrayStaysOnOneLine() {
        assertEquals("{\n  \"a\": [],\n  \"b\": {}\n}", format("{ \"a\" : [ ] , \"b\" : {\r\n} }"));
    }

    @Test
    void numbersAndStringsAreCopiedAsWritten() {
        assertEquals("{\n  \"id\": 12345678901234567890,\n  \"name\": \"\\u00e9\",\n  \"x\": 1.50e+3\n}",
                format("{\"id\":12345678901234567890,\"name\":\"\\u00e9\",\"x\":1.50e+3}"));
    }

    @Test
    void formattingAFormattedTextChangesNothing() {
        String once = format("[{\"a\":{\"b\":[1,2]}},\"" + EMOJI + "\"]");

        assertEquals(once, format(once));
    }

    @Test
    void aTextWithASyntaxErrorIsNotFormattedAndSaysWhy() {
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> format("{\n\"a\": 1,\n}"));

        assertTrue(thrown.isGuestException(), "an Error thrown by the module");
        assertEquals("Error: line 2: trailing comma", thrown.getMessage());
    }
}
