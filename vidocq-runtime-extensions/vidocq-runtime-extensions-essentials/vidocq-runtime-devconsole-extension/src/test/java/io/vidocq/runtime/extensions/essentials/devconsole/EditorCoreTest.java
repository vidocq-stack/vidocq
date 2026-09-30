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
}
