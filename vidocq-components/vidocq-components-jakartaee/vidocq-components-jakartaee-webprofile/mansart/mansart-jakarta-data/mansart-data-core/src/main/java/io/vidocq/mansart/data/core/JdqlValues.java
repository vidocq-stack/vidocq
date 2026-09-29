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
package io.vidocq.mansart.data.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The values of a statement {@code JdqlExecutor.run} runs, converted to the Java type of the attribute they are
 * compared to or assigned: a statement given as text brings its values as text — a literal {@code '2026-09-29'}, a
 * parameter read from a form — and its numbers as whatever a JSON reader made of them.
 *
 * <ul>
 *   <li>A value of the attribute's type is kept, and so is {@code null}.</li>
 *   <li>A {@code String}, for an attribute that is not text: an enum by constant name, the {@code java.time} types by
 *       ISO parsing, the numeric types exactly ({@code BigDecimal} and {@code BigInteger} included), a
 *       {@code Boolean} from {@code true} or {@code false}, a {@code UUID} by {@link UUID#fromString}. Another type
 *       keeps the text, for the database to read.</li>
 *   <li>A {@code Number}, for a numeric attribute: exactly, so that {@code 2.5} is no {@code Integer}.</li>
 *   <li>A collection, the value of an {@code IN :names}: element by element.</li>
 * </ul>
 * A value that does not convert throws {@code MansartDataException("<label>: not a <Type>")} before anything runs.
 * The {@code @Query} path never converts: its values are typed by the method's parameters.
 */
final class JdqlValues {

    /** The numeric types, boxed, that a number or a text converts to exactly. */
    private static final Set<Class<?>> NUMBERS = Set.of(Byte.class, Short.class, Integer.class, Long.class,
            Float.class, Double.class, BigDecimal.class, BigInteger.class);

    private JdqlValues() {}

    /**
     * {@code value} as a value of {@code type}.
     *
     * @param value the value, as given
     * @param type  the Java type of the attribute it is compared to or assigned, a primitive included
     * @param label what an error names: {@code :status} for a parameter, {@code 'LOST'} for a literal
     * @throws MansartDataException when it does not convert
     */
    static Object convert(Object value, Class<?> type, String label) {
        if (value == null || type == null) {
            return value;
        }
        Class<?> target = boxed(type);
        if (value instanceof Collection<?> values) {
            List<Object> out = new ArrayList<>(values.size());
            for (Object element : values) {
                out.add(convert(element, target, label));
            }
            return out;
        }
        if (target.isInstance(value)) {
            return value;
        }
        try {
            if (value instanceof String text) {
                return fromText(text, target);
            }
            if (value instanceof Number number && NUMBERS.contains(target)) {
                return fromNumber(new BigDecimal(number.toString()), target);
            }
        } catch (RuntimeException notConverted) {
            throw new MansartDataException(label + ": not a " + target.getSimpleName(), notConverted);
        }
        return value;
    }

    private static Object fromText(String text, Class<?> target) {
        if (target == String.class || target == Character.class) {
            return text;
        }
        if (target.isEnum()) {
            return constant(target, text);
        }
        if (target == LocalDate.class) {
            return LocalDate.parse(text);
        }
        if (target == LocalDateTime.class) {
            return LocalDateTime.parse(text);
        }
        if (target == LocalTime.class) {
            return LocalTime.parse(text);
        }
        if (target == OffsetDateTime.class) {
            return OffsetDateTime.parse(text);
        }
        if (target == ZonedDateTime.class) {
            return ZonedDateTime.parse(text);
        }
        if (target == Instant.class) {
            return Instant.parse(text);
        }
        if (target == Boolean.class) {
            if (text.equals("true")) {
                return Boolean.TRUE;
            }
            if (text.equals("false")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("neither true nor false");
        }
        if (target == UUID.class) {
            return UUID.fromString(text);
        }
        if (NUMBERS.contains(target)) {
            return fromNumber(new BigDecimal(text.strip()), target);
        }
        return text;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object constant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    private static Object fromNumber(BigDecimal number, Class<?> target) {
        if (target == Integer.class) {
            return number.intValueExact();
        }
        if (target == Long.class) {
            return number.longValueExact();
        }
        if (target == Short.class) {
            return number.shortValueExact();
        }
        if (target == Byte.class) {
            return number.byteValueExact();
        }
        if (target == BigInteger.class) {
            return number.toBigIntegerExact();
        }
        if (target == Double.class) {
            return number.doubleValue();
        }
        if (target == Float.class) {
            return number.floatValue();
        }
        return number;
    }

    private static Class<?> boxed(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == char.class)    return Character.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == float.class)   return Float.class;
        if (c == double.class)  return Double.class;
        return c;
    }
}
