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
package io.vidocq.ravel.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des converters built-in MP Config 3.1 §5.1 / §5.2 (types automatiques scalaires).
 */
@DisplayName("BuiltInConverters — converters MP Config 3.1 §5.1, §5.1.1, §5.2")
class BuiltInConvertersTest {

    // ---------- Boolean §5.1.1 ----------

    @Test
    void boolean_spec_section_5_1_1_truthy_values() {
        var c = new BuiltInConverters.BooleanConverter();
        // true/yes/y/on (case-insensitive) + "1"
        assertTrue(c.convert("true"));
        assertTrue(c.convert("TRUE"));
        assertTrue(c.convert("True"));
        assertTrue(c.convert("yes"));
        assertTrue(c.convert("YES"));
        assertTrue(c.convert("y"));
        assertTrue(c.convert("Y"));
        assertTrue(c.convert("on"));
        assertTrue(c.convert("ON"));
        assertTrue(c.convert("1"));
    }

    @Test
    void boolean_spec_section_5_1_1_falsy_values() {
        var c = new BuiltInConverters.BooleanConverter();
        assertFalse(c.convert("false"));
        assertFalse(c.convert("no"));
        assertFalse(c.convert("0"));
        assertFalse(c.convert("xyz")); // tout ce qui n'est pas truthy
    }

    // ---------- Numeric §5.1 ----------

    @Test
    void integer_converter_handles_signs_and_whitespace() {
        var c = new BuiltInConverters.IntegerConverter();
        assertEquals(42, c.convert("42"));
        assertEquals(-7, c.convert("-7"));
        assertEquals(0, c.convert(" 0 "));
    }

    @Test
    void integer_converter_throws_IAE_on_invalid_input() {
        var c = new BuiltInConverters.IntegerConverter();
        assertThrows(IllegalArgumentException.class, () -> c.convert("abc"));
    }

    @Test
    void long_converter_handles_min_max() {
        var c = new BuiltInConverters.LongConverter();
        assertEquals(Long.MAX_VALUE, c.convert(String.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MIN_VALUE, c.convert(String.valueOf(Long.MIN_VALUE)));
    }

    @Test
    void float_and_double_converters() {
        assertEquals(1.5f, new BuiltInConverters.FloatConverter().convert("1.5"));
        assertEquals(3.14d, new BuiltInConverters.DoubleConverter().convert("3.14"));
    }

    @Test
    void short_and_byte_converters() {
        assertEquals((short) 7, new BuiltInConverters.ShortConverter().convert("7"));
        assertEquals((byte) 127, new BuiltInConverters.ByteConverter().convert("127"));
    }

    @Test
    void character_converter_requires_single_char() {
        var c = new BuiltInConverters.CharacterConverter();
        assertEquals('a', c.convert("a"));
        assertThrows(IllegalArgumentException.class, () -> c.convert("ab"));
    }

    // ---------- Class<?> §5.1 ----------

    @Test
    void class_converter_loads_via_context_classloader() {
        var c = new BuiltInConverters.ClassConverter();
        assertEquals(String.class, c.convert("java.lang.String"));
    }

    @Test
    void class_converter_throws_IAE_when_not_found() {
        var c = new BuiltInConverters.ClassConverter();
        assertThrows(IllegalArgumentException.class,
                () -> c.convert("does.not.exist.NoSuchClass"));
    }

    // ---------- Optional* §5.1 ----------

    @Test
    void optionalInt_converter_present_and_empty() {
        var c = new BuiltInConverters.OptionalIntConverter();
        assertEquals(OptionalInt.of(42), c.convert("42"));
        assertEquals(OptionalInt.empty(), c.convert(""));
    }

    @Test
    void optionalLong_converter_present_and_empty() {
        var c = new BuiltInConverters.OptionalLongConverter();
        assertEquals(OptionalLong.of(42L), c.convert("42"));
        assertEquals(OptionalLong.empty(), c.convert(""));
    }

    @Test
    void optionalDouble_converter_present_and_empty() {
        var c = new BuiltInConverters.OptionalDoubleConverter();
        assertEquals(OptionalDouble.of(2.5), c.convert("2.5"));
        assertEquals(OptionalDouble.empty(), c.convert(""));
    }

    // ---------- Network §5.1 ----------

    @Test
    void uri_url_inetaddress_converters() {
        assertEquals(URI.create("https://example.org/x"),
                new BuiltInConverters.URIConverter().convert("https://example.org/x"));

        URL url = new BuiltInConverters.URLConverter().convert("https://example.org/y");
        assertNotNull(url);
        assertEquals("example.org", url.getHost());

        InetAddress addr = new BuiltInConverters.InetAddressConverter().convert("127.0.0.1");
        assertNotNull(addr);
        assertEquals("127.0.0.1", addr.getHostAddress());
    }

    @Test
    void uri_converter_throws_IAE_on_invalid_syntax() {
        var c = new BuiltInConverters.URIConverter();
        assertThrows(IllegalArgumentException.class, () -> c.convert(":::"));
    }

    // ---------- java.time §5.1 ----------

    @Test
    void time_converters_iso_format() {
        assertEquals(Duration.ofSeconds(30),
                new BuiltInConverters.DurationConverter().convert("PT30S"));
        assertEquals(Period.ofDays(7),
                new BuiltInConverters.PeriodConverter().convert("P7D"));
        assertEquals(LocalDate.of(2026, 5, 7),
                new BuiltInConverters.LocalDateConverter().convert("2026-05-07"));
        assertEquals(LocalTime.of(12, 30),
                new BuiltInConverters.LocalTimeConverter().convert("12:30"));
        assertEquals(LocalDateTime.of(2026, 5, 7, 12, 30),
                new BuiltInConverters.LocalDateTimeConverter().convert("2026-05-07T12:30"));
        assertEquals(Instant.parse("2026-05-07T10:00:00Z"),
                new BuiltInConverters.InstantConverter().convert("2026-05-07T10:00:00Z"));
        assertEquals(OffsetTime.parse("12:30+02:00"),
                new BuiltInConverters.OffsetTimeConverter().convert("12:30+02:00"));
        assertEquals(OffsetDateTime.parse("2026-05-07T12:30+02:00"),
                new BuiltInConverters.OffsetDateTimeConverter().convert("2026-05-07T12:30+02:00"));
        assertEquals(ZonedDateTime.parse("2026-05-07T12:30+02:00[Europe/Paris]"),
                new BuiltInConverters.ZonedDateTimeConverter().convert("2026-05-07T12:30+02:00[Europe/Paris]"));
    }

    @Test
    void duration_converter_throws_IAE_on_invalid() {
        var c = new BuiltInConverters.DurationConverter();
        assertThrows(IllegalArgumentException.class, () -> c.convert("not-a-duration"));
    }

    // ---------- table de tous les built-in ----------

    @Test
    void all_returns_immutable_map_covering_spec_5_1() {
        var map = BuiltInConverters.all();
        // Tous les types §5.1 explicitement listés
        for (Class<?> t : new Class<?>[] {
                String.class, Boolean.class, boolean.class,
                Integer.class, int.class, Long.class, long.class,
                Float.class, float.class, Double.class, double.class,
                Short.class, short.class, Byte.class, byte.class,
                Character.class, char.class, Class.class,
                OptionalInt.class, OptionalLong.class, OptionalDouble.class,
                URI.class, URL.class, InetAddress.class,
                Duration.class, Period.class,
                LocalDate.class, LocalTime.class, LocalDateTime.class, Instant.class,
                OffsetTime.class, OffsetDateTime.class, ZonedDateTime.class
        }) {
            assertNotNull(map.get(t), "Built-in converter manquant pour " + t.getName());
        }
        // Map immutable
        assertThrows(UnsupportedOperationException.class, () -> map.put(Object.class, null));
    }

    @Test
    void boxed_and_primitive_share_same_converter() {
        var map = BuiltInConverters.all();
        assertEquals(map.get(int.class), map.get(Integer.class));
        assertEquals(map.get(long.class), map.get(Long.class));
        assertEquals(map.get(boolean.class), map.get(Boolean.class));
    }
}

