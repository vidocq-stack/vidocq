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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The dev MCP's JSON reader and writer: every kind of value, strictly, and back. */
class JsonValuesTest {

    @Test
    void readsEveryKindOfValueInDocumentOrder() {
        Object value = JsonValues.parse(" {\"b\":[1,-2.5,1e3,true,false,null],\"a\":\"\\u00e9\\n\\\"\",\"c\":{}} ",
                8);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("b", Arrays.asList(1L, -2.5, 1000.0, true, false, null));
        expected.put("a", "\u00e9\n\"");
        expected.put("c", Map.of());
        assertEquals(expected, value);
        assertEquals(List.of("b", "a", "c"), List.copyOf(((Map<?, ?>) value).keySet()));
    }

    @Test
    void anIntegerPastALongIsADouble() {
        assertEquals(1.0e20, JsonValues.parse("100000000000000000000", 1));
    }

    @Test
    void refusesWhatJsonDoesNotAllow() {
        for (String text : List.of("", "{", "{\"a\":1,\"a\":2}", "{\"a\":1} x", "[01]", "[1.]", "{a:1}", "'a'",
                "\"a\u0001\"", "\"\\x\"", "tru", "[1,]", "-")) {
            assertThrows(IllegalArgumentException.class, () -> JsonValues.parse(text, 8), text);
        }
    }

    @Test
    void refusesValuesNestedPastTheLimit() {
        assertEquals(List.of(List.of(List.of())), JsonValues.parse("[[[]]]", 3));
        assertThrows(IllegalArgumentException.class, () -> JsonValues.parse("[[[[]]]]", 3));
        assertThrows(IllegalArgumentException.class, () -> JsonValues.parse("{\"a\":{\"b\":{\"c\":{}}}}", 3));
    }

    @Test
    void writesBackWhatItRead() {
        String text = "{\"a\":[1,2.5,\"x\\\"y\",null,true],\"b\":{},\"c\":[]}";
        JsonWriter out = new JsonWriter();

        JsonValues.write(out, JsonValues.parse(text, 8));

        assertEquals(text, out.toString());
    }

    @Test
    void prettyIndentsTwoSpacesALevel() {
        assertEquals("{\n  \"a\": [\n    1,\n    \"x\"\n  ],\n  \"b\": {},\n  \"c\": null\n}",
                JsonValues.pretty(JsonValues.parse("{\"a\":[1,\"x\"],\"b\":{},\"c\":null}", 8)));
    }
}
