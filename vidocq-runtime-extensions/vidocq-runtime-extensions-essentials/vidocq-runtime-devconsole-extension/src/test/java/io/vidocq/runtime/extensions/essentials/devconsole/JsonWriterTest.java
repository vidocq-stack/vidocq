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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The console's JSON, written by hand: what RFC 8259 and a browser's {@code JSON.parse} accept. */
class JsonWriterTest {

    @Test
    void nestsObjectsAndArraysWithTheirCommas() {
        String json = new JsonWriter()
                .beginObject()
                .name("a").value(1)
                .name("b").beginArray().value("x").beginObject().endObject().beginArray().endArray().value(true)
                .endArray()
                .name("c").beginObject().name("d").nullValue().endObject()
                .endObject()
                .toString();

        assertEquals("{\"a\":1,\"b\":[\"x\",{},[],true],\"c\":{\"d\":null}}", json);
        assertEquals(Map.of("a", 1L, "b", List.of("x", Map.of(), List.of(), true), "c", java.util.Collections
                .singletonMap("d", null)), Json.parse(json));
    }

    @Test
    void escapesWhatAStringCannotHoldAndNothingElse() {
        String nasty = "quote \" backslash \\ slash / tab \t line \n return \r nul \u0000 esc \u001B del \u007F "
                + "separators \u2028\u2029 emoji \uD83D\uDE00 lone \uD800 end \uDC00 \u00E9";

        String json = new JsonWriter().value(nasty).toString();

        assertEquals("\"quote \\\" backslash \\\\ slash / tab \\t line \\n return \\r nul \\u0000 esc \\u001b "
                + "del \\u007f separators \\u2028\\u2029 emoji \uD83D\uDE00 lone \\ud800 end \\udc00 \u00E9\"", json);
        assertEquals(nasty, Json.parse(json), "read back as written");
    }

    @Test
    void writesNumbersAsJsonHasThem() {
        String json = new JsonWriter().beginArray()
                .value(3.0).value(0.25).value(-1.5e-7).value(1e300).value(Long.MIN_VALUE).value(Double.NaN)
                .value(Double.POSITIVE_INFINITY).value((String) null)
                .endArray().toString();

        assertEquals("[3,0.25,-1.5E-7,1.0E300,-9223372036854775808,null,null,null]", json);
        assertEquals(List.of(3L, 0.25, -1.5e-7, 1e300, Long.MIN_VALUE), ((List<?>) Json.parse(json)).subList(0, 5));
    }
}
