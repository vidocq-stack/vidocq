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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The choice a panel offers above its sub-tabs, in the page's pure half, {@code editor-core.js}, run by GraalJS as the
 * ES module the page loads: the selector a snapshot panel declares, its choices, the one kept across polls, the
 * fields a form still shows, and the choice a replay switches to.
 */
class SelectorCoreTest {

    private static final String UNIT = "{\"argument\":\"unit\",\"label\":\"Persistence unit\"}";
    private static final String PANEL = "{\"id\":\"noether-persistence\",\"selector\":" + UNIT
            + ",\"sample\":{\"values\":[],\"groups\":[{\"name\":\"ledger\",\"values\":[]},"
            + "{\"name\":\"archive\",\"values\":[]}]}}";

    private static Context context;
    private static Value exports;
    private static Value json;

    @BeforeAll
    static void loadTheModule() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = SelectorCoreTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code function} of the module called with each argument parsed from JSON, its result as JSON. */
    private static String call(String function, String... arguments) {
        Object[] values = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            values[i] = json.invokeMember("parse", arguments[i]);
        }
        Value result = exports.getMember(function).execute(values);
        return result.isNull() ? "null" : json.invokeMember("stringify", result).asString();
    }

    @Test
    void aPanelDeclaresItsSelector() {
        assertEquals(UNIT, call("selectorOf", PANEL));
    }

    @Test
    void aMissingOrMalformedSelectorIsNone() {
        for (String odd : new String[] {"{}", "{\"selector\":null}", "{\"selector\":\"unit\"}",
                "{\"selector\":{\"argument\":\"Unit\",\"label\":\"Persistence unit\"}}",
                "{\"selector\":{\"argument\":\"unit\",\"label\":\" \"}}",
                "{\"selector\":{\"argument\":\"unit\"}}"}) {
            assertEquals("null", call("selectorOf", odd), odd);
        }
    }

    @Test
    void theChoicesAreTheGroupNamesOfTheSampleInOrder() {
        assertEquals("[\"ledger\",\"archive\"]", call("choicesOf", PANEL));
    }

    @Test
    void aSampleThatFailedOrHasNoGroupOffersNoChoice() {
        for (String odd : new String[] {"{}", "{\"sample\":null}", "{\"sample\":{\"error\":\"IllegalStateException\"}}",
                "{\"sample\":{\"values\":[]}}", "{\"sample\":{\"groups\":[{\"values\":[]}]}}"}) {
            assertEquals("[]", call("choicesOf", odd), odd);
        }
    }

    @Test
    void theKeptChoiceStaysWhileItIsAChoice() {
        assertEquals("\"archive\"", call("pickChoice", "[\"ledger\",\"archive\"]", "\"archive\""));
    }

    @Test
    void aVanishedChoiceFallsBackToTheFirst() {
        assertEquals("\"ledger\"", call("pickChoice", "[\"ledger\",\"archive\"]", "\"journal\""));
        assertEquals("\"ledger\"", call("pickChoice", "[\"ledger\",\"archive\"]", "null"));
        assertEquals("null", call("pickChoice", "[]", "\"ledger\""));
    }

    @Test
    void theFormLeavesTheSelectorsArgumentOut() {
        String action = "{\"id\":\"jpql\",\"arguments\":[{\"name\":\"unit\",\"label\":\"Unit\"},"
                + "{\"name\":\"query\",\"label\":\"Query\"}]}";

        assertEquals("[{\"name\":\"query\",\"label\":\"Query\"}]", call("formArguments", action, UNIT));
        assertEquals("true", call("takesChoice", action, UNIT));
    }

    @Test
    void anActionWithoutTheArgumentKeepsAllItsFields() {
        String action = "{\"id\":\"ping\",\"arguments\":[{\"name\":\"query\",\"label\":\"Query\"}]}";

        assertEquals("[{\"name\":\"query\",\"label\":\"Query\"}]", call("formArguments", action, UNIT));
        assertEquals("false", call("takesChoice", action, UNIT));
        assertEquals("[{\"name\":\"query\",\"label\":\"Query\"}]", call("formArguments", action, "null"),
                "no selector, no field left out");
    }

    @Test
    void aReplayOfAChoiceSwitchesToIt() {
        assertEquals("\"archive\"", call("replayChoice", "{\"unit\":\"archive\",\"query\":\"SELECT 1\"}", UNIT,
                "[\"ledger\",\"archive\"]"));
    }

    @Test
    void aReplayOfAnotherValueOrWithoutTheArgumentSwitchesNothing() {
        assertEquals("null", call("replayChoice", "{\"unit\":\"journal\"}", UNIT, "[\"ledger\",\"archive\"]"));
        assertEquals("null", call("replayChoice", "{\"query\":\"SELECT 1\"}", UNIT, "[\"ledger\",\"archive\"]"));
        assertEquals("null", call("replayChoice", "{\"unit\":\"archive\"}", "null", "[\"ledger\",\"archive\"]"));
    }
}
