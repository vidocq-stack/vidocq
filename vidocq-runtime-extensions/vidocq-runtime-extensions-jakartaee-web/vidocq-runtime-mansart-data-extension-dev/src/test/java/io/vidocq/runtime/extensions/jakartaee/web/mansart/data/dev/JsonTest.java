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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The panel's own JSON: a tree of maps, lists, strings, BigDecimals, booleans and nulls. */
class JsonTest {

    @Test
    void readsAnObjectKeepingItsOrderAndItsNumbersText() {
        Object parsed = Json.parse(" {\"b\": 1, \"a\": [true, false, null, \"x\"], \"n\": -2.50e0, \"o\": {}} ");

        Map<?, ?> object = assertInstanceOf(Map.class, parsed);
        assertEquals(List.of("b", "a", "n", "o"), List.copyOf(object.keySet()));
        assertEquals(new BigDecimal("1"), object.get("b"));
        assertEquals(Arrays.asList(true, false, null, "x"), object.get("a"));
        assertEquals(new BigDecimal("-2.50"), object.get("n"));
        assertEquals(Map.of(), object.get("o"));
    }

    @Test
    void readsEscapes() {
        assertEquals("a\"b\\c/d\n\t\u00e9", Json.parse("\"a\\\"b\\\\c\\/d\\n\\t\\u00e9\""));
    }

    @Test
    void refusesWhatIsNotJson() {
        for (String text : List.of("", "{", "{\"a\" 1}", "[1,]", "01", "1.", "tru", "\"a", "{} x", "\"\u0001\"",
                "\"\\x\"", "\"\\u12G4\"")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(text), text);
        }
    }

    @Test
    void refusesADeeperNestingThanTheConsole() {
        assertInstanceOf(List.class, Json.parse("[".repeat(64) + "]".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(65) + "]".repeat(65)));
    }

    @Test
    void writesCompactJson() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("s", "q\"\\\n\u0001");
        tree.put("n", new BigDecimal("2.50"));
        tree.put("l", 3L);
        tree.put("b", true);
        tree.put("z", null);
        tree.put("a", List.of(1, "x"));
        tree.put("nan", Double.NaN);

        assertEquals("{\"s\":\"q\\\"\\\\\\n\\u0001\",\"n\":2.50,\"l\":3,\"b\":true,\"z\":null,\"a\":[1,\"x\"],"
                + "\"nan\":\"NaN\"}", Json.write(tree));
    }

    @Test
    void whatItWritesItReads() {
        String text = "{\"a\":[1,2.50,{\"b\":\"c\"}],\"d\":null}";

        assertEquals(text, Json.write(Json.parse(text)));
    }
}
