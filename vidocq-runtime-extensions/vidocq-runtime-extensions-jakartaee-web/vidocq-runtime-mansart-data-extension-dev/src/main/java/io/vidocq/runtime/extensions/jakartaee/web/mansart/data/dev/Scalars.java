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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The parameter types of spec §4 that are no entity: their JSON Schema, their conversion from the JSON the page
 * sends, and the JSON of a value a method returns (spec §6). A value never converts approximately: a fraction for an
 * integer, a number out of range, an unknown constant, a malformed date are refused with why.
 */
final class Scalars {

    private static final Set<Class<?>> INTEGERS = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class, byte.class, Byte.class, BigInteger.class);
    private static final Set<Class<?>> NUMBERS =
            Set.of(double.class, Double.class, float.class, Float.class, BigDecimal.class);
    private static final Set<Class<?>> DATE_TIMES =
            Set.of(LocalDateTime.class, Instant.class, OffsetDateTime.class, ZonedDateTime.class);
    private static final Map<Class<?>, String> PRIMITIVES = Map.of(Integer.class, "int", Long.class, "long",
            Short.class, "short", Byte.class, "byte", Double.class, "double", Float.class, "float");
    /** The most integer digits read: past it a value is out of the range of every type, BigInteger included. */
    private static final int MAX_DIGITS = 1000;

    private Scalars() {}

    /** A JSON object of these names and values, in this order: {@code object("type", "string")}. */
    static Map<String, Object> object(Object... namesAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            object.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return object;
    }

    /** The JSON Schema of {@code type}, or {@code null} when §4 does not support it. */
    static Map<String, Object> schema(Class<?> type) {
        if (type == String.class) {
            return object("type", "string");
        }
        if (type == char.class || type == Character.class) {
            return object("type", "string", "maxLength", 1);
        }
        if (INTEGERS.contains(type)) {
            return object("type", "integer");
        }
        if (NUMBERS.contains(type)) {
            return object("type", "number");
        }
        if (type == boolean.class || type == Boolean.class) {
            return object("type", "boolean");
        }
        if (type.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            return object("type", "string", "enum", names);
        }
        if (type == LocalDate.class) {
            return object("type", "string", "format", "date");
        }
        if (type == LocalTime.class) {
            return object("type", "string", "format", "time");
        }
        if (DATE_TIMES.contains(type)) {
            return object("type", "string", "format", "date-time");
        }
        if (type == UUID.class) {
            return object("type", "string", "format", "uuid");
        }
        return null;
    }

    /**
     * {@code json}, a value of {@link Json#parse}, as a {@code type}: the exact wrapper of a primitive, so that a
     * method handle or a reflective call unboxes it.
     *
     * @param name the parameter or property it is for, which a refusal names
     * @throws ArgumentException when it does not convert
     */
    static Object fromJson(Class<?> type, Object json, String name) throws ArgumentException {
        if (json == null) {
            if (type.isPrimitive()) {
                throw new ArgumentException(name, "null is not allowed for " + type.getName());
            }
            return null;
        }
        if (type == String.class) {
            return text(json, name);
        }
        if (type == char.class || type == Character.class) {
            String text = text(json, name);
            if (text.length() != 1) {
                throw new ArgumentException(name, "not one character");
            }
            return text.charAt(0);
        }
        if (INTEGERS.contains(type)) {
            return integer(type, json, name);
        }
        if (type == double.class || type == Double.class) {
            double value = number(json, name, "not a number").doubleValue();
            if (Double.isInfinite(value)) {
                throw outOfRange(type, name);
            }
            return value;
        }
        if (type == float.class || type == Float.class) {
            float value = number(json, name, "not a number").floatValue();
            if (Float.isInfinite(value)) {
                throw outOfRange(type, name);
            }
            return value;
        }
        if (type == BigDecimal.class) {
            return number(json, name, "not a number");
        }
        if (type == boolean.class || type == Boolean.class) {
            if (json instanceof Boolean flag) {
                return flag;
            }
            throw new ArgumentException(name, "not a boolean");
        }
        if (type.isEnum()) {
            String text = text(json, name);
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(text)) {
                    return constant;
                }
            }
            throw new ArgumentException(name, "no constant " + Failures.cut(text, 60) + " in " + type.getSimpleName());
        }
        if (type == LocalDate.class) {
            return time(json, name, "date", LocalDate::parse);
        }
        if (type == LocalTime.class) {
            return time(json, name, "time", LocalTime::parse);
        }
        if (type == LocalDateTime.class) {
            return time(json, name, "date-time", LocalDateTime::parse);
        }
        if (type == Instant.class) {
            return time(json, name, "date-time", Instant::parse);
        }
        if (type == OffsetDateTime.class) {
            return time(json, name, "date-time", OffsetDateTime::parse);
        }
        if (type == ZonedDateTime.class) {
            return time(json, name, "date-time", ZonedDateTime::parse);
        }
        if (type == UUID.class) {
            try {
                return UUID.fromString(text(json, name));
            } catch (IllegalArgumentException malformed) {
                throw new ArgumentException(name, "not a UUID");
            }
        }
        throw new ArgumentException(name, type.getSimpleName() + " is not supported");
    }

    /**
     * The JSON of a value a method returns (spec §6): {@code null}, a number, a boolean or a string as it is, an enum
     * as its name, a {@code char} as a string, anything else — a {@code java.time} value, a {@code UUID} — as its
     * text; a number JSON cannot hold, such as {@code NaN}, as its text too.
     */
    static Object toJson(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Character c -> String.valueOf(c);
            case Boolean flag -> flag;
            case Double d -> d.isNaN() || d.isInfinite() ? d.toString() : d;
            case Float f -> f.isNaN() || f.isInfinite() ? f.toString() : f;
            case Number number -> number;
            case Enum<?> constant -> constant.name();
            default -> value.toString();
        };
    }

    private static String text(Object json, String name) throws ArgumentException {
        if (json instanceof String text) {
            return text;
        }
        throw new ArgumentException(name, "not a string");
    }

    private static BigDecimal number(Object json, String name, String why) throws ArgumentException {
        if (json instanceof BigDecimal number) {
            return number;
        }
        if (json instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new ArgumentException(name, why);
    }

    private static Object integer(Class<?> type, Object json, String name) throws ArgumentException {
        BigDecimal number = number(json, name, "not an integer");
        if (number.precision() - number.scale() > MAX_DIGITS) {
            throw outOfRange(type, name);
        }
        BigInteger value;
        try {
            value = number.toBigIntegerExact();
        } catch (ArithmeticException fraction) {
            throw new ArgumentException(name, "not an integer");
        }
        try {
            if (type == int.class || type == Integer.class) {
                return value.intValueExact();
            }
            if (type == long.class || type == Long.class) {
                return value.longValueExact();
            }
            if (type == short.class || type == Short.class) {
                return value.shortValueExact();
            }
            if (type == byte.class || type == Byte.class) {
                return value.byteValueExact();
            }
            return value;
        } catch (ArithmeticException tooLarge) {
            throw outOfRange(type, name);
        }
    }

    private static ArgumentException outOfRange(Class<?> type, String name) {
        String shown = type.isPrimitive() ? type.getName() : PRIMITIVES.getOrDefault(type, type.getSimpleName());
        return new ArgumentException(name, "out of range for " + shown);
    }

    private static Object time(Object json, String name, String what, Function<String, Object> parse)
            throws ArgumentException {
        try {
            return parse.apply(text(json, name));
        } catch (DateTimeParseException malformed) {
            throw new ArgumentException(name, "not an ISO " + what);
        }
    }
}
