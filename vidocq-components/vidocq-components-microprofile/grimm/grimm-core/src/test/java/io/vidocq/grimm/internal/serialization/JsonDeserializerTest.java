/*
 * Copyright 2026 Vidocq contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The JSON reader of static files and configuration values (spec §4.2, RFC 8259).
 */
class JsonDeserializerTest {

    @Test
    void integerBeyondALongIsReadAsABigInteger() {
        // BUG-20261028-22: RFC 8259 §6 sets no range on numbers; a JSON Schema bound may exceed a long.
        assertEquals(new BigInteger("123456789012345678901"), JsonDeserializer.parseRaw("123456789012345678901"));
        assertEquals(new BigInteger("-123456789012345678901"), JsonDeserializer.parseRaw("-123456789012345678901"));

        Schema schema = JsonDeserializer.toSchema(JsonDeserializer.parseRaw("{\"maximum\": 99999999999999999999}"));

        assertEquals(new BigDecimal("99999999999999999999"), schema.getMaximum());
    }

    @Test
    void scalarsAndNumbers() {
        assertEquals(Arrays.asList(true, false, null, -1L, 2.5, 1.0e3, 2.0e-2),
                JsonDeserializer.parseRaw(" [true, false, null, -1, 2.5, 1e3, 2E-2] "));
    }

    @Test
    void stringEscapes() {
        assertEquals("\"\\/\b\f\n\r\tA", JsonDeserializer.parseRaw("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\""));
    }

    @Test
    void objectsAndArrays() {
        assertEquals(Map.of("a", List.of(), "b", Map.of(), "c", List.of(Map.of("d", 1L))),
                JsonDeserializer.parseRaw("{\"a\": [], \"b\": {}, \"c\": [ {\"d\" : 1} ]}"));
    }

    @Test
    void malformedInputIsRejected() {
        for (String json : List.of("", "[1,", "{\"a\" 1}", "{\"a\": 1,}", "[1 2]", "\"open", "\"\\x\"", "\"\\u00\"",
                "\"\\", "tru", "nul", "fals", "@", "1 2", "{1: 2}")) {
            assertThrows(IllegalArgumentException.class, () -> JsonDeserializer.parseRaw(json), json);
        }
    }

    @Test
    void nullInputIsRejected() {
        JsonDeserializer deserializer = new JsonDeserializer();
        assertThrows(NullPointerException.class, () -> deserializer.deserialize(null));
        assertThrows(NullPointerException.class, () -> JsonDeserializer.parseRaw(null));
    }
}
