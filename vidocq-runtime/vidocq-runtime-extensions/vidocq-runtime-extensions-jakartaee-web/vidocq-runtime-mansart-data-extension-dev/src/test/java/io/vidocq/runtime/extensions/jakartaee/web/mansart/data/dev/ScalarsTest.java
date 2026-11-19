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
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The types of spec §4: their schema, and their conversion from and to JSON. */
class ScalarsTest {

    enum Level { LOW, HIGH }

    private static Object convert(Class<?> type, String json) throws ArgumentException {
        return Scalars.fromJson(type, Json.parse(json), "x");
    }

    private static String refusal(Class<?> type, String json) {
        return assertThrows(ArgumentException.class, () -> convert(type, json)).getMessage();
    }

    private static String schema(Class<?> type) {
        return Json.write(Scalars.schema(type));
    }

    @Test
    void theSchemaOfEachSupportedType() {
        assertEquals("{\"type\":\"string\"}", schema(String.class));
        assertEquals("{\"type\":\"string\",\"maxLength\":1}", schema(char.class));
        assertEquals("{\"type\":\"string\",\"maxLength\":1}", schema(Character.class));
        for (Class<?> type : List.of(int.class, Integer.class, long.class, Long.class, short.class, Short.class,
                byte.class, Byte.class, BigInteger.class)) {
            assertEquals("{\"type\":\"integer\"}", schema(type), type.getName());
        }
        for (Class<?> type : List.of(double.class, Double.class, float.class, Float.class, BigDecimal.class)) {
            assertEquals("{\"type\":\"number\"}", schema(type), type.getName());
        }
        assertEquals("{\"type\":\"boolean\"}", schema(boolean.class));
        assertEquals("{\"type\":\"boolean\"}", schema(Boolean.class));
        assertEquals("{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}", schema(Level.class));
        assertEquals("{\"type\":\"string\",\"format\":\"date\"}", schema(LocalDate.class));
        for (Class<?> type : List.of(LocalDateTime.class, Instant.class, OffsetDateTime.class, ZonedDateTime.class)) {
            assertEquals("{\"type\":\"string\",\"format\":\"date-time\"}", schema(type), type.getName());
        }
        assertEquals("{\"type\":\"string\",\"format\":\"time\"}", schema(LocalTime.class));
        assertEquals("{\"type\":\"string\",\"format\":\"uuid\"}", schema(UUID.class));
    }

    @Test
    void otherTypesHaveNoSchema() {
        assertNull(Scalars.schema(List.class));
        assertNull(Scalars.schema(Object.class));
        assertNull(Scalars.schema(int[].class));
    }

    @Test
    void textsAndCharacters() throws Exception {
        assertEquals("bolt", convert(String.class, "\"bolt\""));
        assertEquals('A', convert(char.class, "\"A\""));
        assertEquals("x: not one character", refusal(Character.class, "\"AB\""));
        assertEquals("x: not a string", refusal(String.class, "42"));
    }

    @Test
    void integersExactlyAndInRange() throws Exception {
        assertEquals(42, convert(int.class, "42"));
        assertEquals(9007199254740993L, convert(Long.class, "9007199254740993"));
        assertEquals((short) -3, convert(short.class, "-3"));
        assertEquals((byte) 127, convert(Byte.class, "127"));
        assertEquals(new BigInteger("123456789012345678901234567890"),
                convert(BigInteger.class, "123456789012345678901234567890"));
        assertEquals(1, convert(int.class, "1.0"), "an integral value written with a fraction part");
        assertEquals("x: not an integer", refusal(int.class, "1.5"));
        assertEquals("x: not an integer", refusal(long.class, "\"12\""));
        assertEquals("x: out of range for int", refusal(Integer.class, "2147483648"));
        assertEquals("x: out of range for byte", refusal(byte.class, "128"));
        assertEquals("x: out of range for long", refusal(long.class, "1e5000"));
    }

    @Test
    void numbers() throws Exception {
        assertEquals(2.5d, convert(double.class, "2.5"));
        assertEquals(0.1f, convert(Float.class, "0.1"));
        assertEquals(new BigDecimal("2.50"), convert(BigDecimal.class, "2.50"), "the text kept, its scale too");
        assertEquals("x: out of range for double", refusal(double.class, "1e400"));
        assertEquals("x: out of range for float", refusal(float.class, "1e39"));
        assertEquals("x: not a number", refusal(BigDecimal.class, "\"2.50\""));
    }

    @Test
    void booleansAndEnums() throws Exception {
        assertEquals(true, convert(boolean.class, "true"));
        assertEquals("x: not a boolean", refusal(Boolean.class, "\"true\""));
        assertEquals(Level.HIGH, convert(Level.class, "\"HIGH\""));
        assertEquals("x: no constant MEDIUM in Level", refusal(Level.class, "\"MEDIUM\""));
    }

    @Test
    void timesInIso() throws Exception {
        assertEquals(LocalDate.of(2026, 9, 28), convert(LocalDate.class, "\"2026-09-28\""));
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 15, 30),
                convert(LocalDateTime.class, "\"2026-09-28T10:15:30\""));
        assertEquals(Instant.parse("2026-09-28T10:15:30Z"), convert(Instant.class, "\"2026-09-28T10:15:30Z\""));
        assertEquals(OffsetDateTime.parse("2026-09-28T10:15:30+02:00"),
                convert(OffsetDateTime.class, "\"2026-09-28T10:15:30+02:00\""));
        assertEquals(ZonedDateTime.parse("2026-09-28T10:15:30+02:00[Europe/Paris]"),
                convert(ZonedDateTime.class, "\"2026-09-28T10:15:30+02:00[Europe/Paris]\""));
        assertEquals(LocalTime.of(10, 15), convert(LocalTime.class, "\"10:15\""));
        assertEquals("x: not an ISO date", refusal(LocalDate.class, "\"28/09/2026\""));
        assertEquals("x: not an ISO date-time", refusal(Instant.class, "\"2026-09-28\""));
        assertEquals("x: not an ISO time", refusal(LocalTime.class, "\"25:00\""));
    }

    @Test
    void uuids() throws Exception {
        UUID id = UUID.fromString("5f0c1d2e-3a4b-4c5d-8e6f-7a8b9c0d1e2f");

        assertEquals(id, convert(UUID.class, "\"" + id + "\""));
        assertEquals("x: not a UUID", refusal(UUID.class, "\"nope\""));
    }

    @Test
    void nullForABoxNeverForAPrimitive() throws Exception {
        assertNull(convert(Integer.class, "null"));
        assertNull(convert(LocalDate.class, "null"));
        assertEquals("x: null is not allowed for int", refusal(int.class, "null"));
    }

    @Test
    void anUnsupportedTypeIsRefused() {
        assertEquals("x: List is not supported", refusal(List.class, "[]"));
    }

    @Test
    void valuesToJson() {
        assertEquals("HIGH", Scalars.toJson(Level.HIGH));
        assertEquals("2026-09-28T10:15:30Z", Scalars.toJson(Instant.parse("2026-09-28T10:15:30Z")));
        assertEquals("A", Scalars.toJson('A'));
        assertEquals(42L, Scalars.toJson(42L));
        assertEquals("NaN", Scalars.toJson(Double.NaN));
        assertNull(Scalars.toJson(null));
        assertEquals("[1, 2]", Scalars.toJson(new StringBuilder("[1, 2]")), "anything else as its text");
    }
}
