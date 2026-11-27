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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import jakarta.persistence.Parameter;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A statement the JPQL tab runs, read from the json argument {@value #ARGUMENT}: {@code query}, its text, and
 * {@code params}, its named parameters as a JSON object or the text of one, each a value or a list of values.
 *
 * @param query  the statement, stripped
 * @param params its named parameters, in the order given
 */
record Statement(String query, Map<String, Object> params) {

    /** The json argument of the tab's actions. */
    static final String ARGUMENT = "statement";

    /** The member of the json argument that holds the query. */
    static final String QUERY_MEMBER = "query";
    /** The member of the json argument that holds the named parameters. */
    static final String PARAMS_MEMBER = "params";
    private static final Pattern WRITE = Pattern.compile("(?i)(UPDATE|DELETE)\\b");
    private static final Pattern DELETE = Pattern.compile("(?i)DELETE\\b");

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(long.class, Long.class, int.class, Integer.class,
            short.class, Short.class, byte.class, Byte.class, double.class, Double.class, float.class, Float.class,
            boolean.class, Boolean.class);

    private static final Map<Class<?>, Function<BigDecimal, Object>> NUMBERS = Map.of(
            Long.class, BigDecimal::longValueExact,
            Integer.class, BigDecimal::intValueExact,
            Short.class, BigDecimal::shortValueExact,
            Byte.class, BigDecimal::byteValueExact,
            Double.class, BigDecimal::doubleValue,
            Float.class, BigDecimal::floatValue,
            BigInteger.class, BigDecimal::toBigIntegerExact,
            BigDecimal.class, number -> number);

    private static final Map<Class<?>, Function<String, Object>> TEXTS = Map.of(
            LocalDate.class, LocalDate::parse,
            LocalDateTime.class, LocalDateTime::parse,
            LocalTime.class, LocalTime::parse,
            OffsetDateTime.class, OffsetDateTime::parse,
            Instant.class, Instant::parse,
            UUID.class, UUID::fromString,
            Boolean.class, Statement::bool);

    /**
     * The statement {@code sent} holds.
     *
     * @throws Refused naming the member that is wrong, when {@code sent} is no statement
     */
    static Statement read(String sent) throws Refused {
        Map<?, ?> members = object(ARGUMENT, parse(ARGUMENT, sent));
        for (Object name : members.keySet()) {
            if (!QUERY_MEMBER.equals(name) && !PARAMS_MEMBER.equals(name)) {
                throw new Refused(name + ": unknown argument");
            }
        }
        String query = text(members.get(QUERY_MEMBER));
        if (query == null || query.isBlank()) {
            throw new Refused(QUERY_MEMBER + ": missing");
        }
        return new Statement(query.strip(), params(members.get(PARAMS_MEMBER)));
    }

    /** Whether the statement is an {@code UPDATE} or a {@code DELETE}. */
    boolean isWrite() {
        return WRITE.matcher(query).lookingAt();
    }

    /** Whether the statement is a {@code DELETE}. */
    boolean isDelete() {
        return DELETE.matcher(query).lookingAt();
    }

    /** Sets each parameter of {@code target}, its value converted to the type the query gives it. */
    void bind(Query target) {
        params.forEach((name, value) -> target.setParameter(name, bind(name, value, declared(target, name))));
    }

    /**
     * {@code value} as a {@code type}: a number as the number type, a text as a date, a time, a {@link UUID}, a
     * boolean or a constant of an enum, each value of a list so; unchanged when {@code type} is {@code null},
     * {@link Object} or a type it already has.
     *
     * @throws IllegalArgumentException naming the parameter, when {@code value} cannot be a {@code type}
     */
    static Object bind(String name, Object value, Class<?> type) {
        if (value instanceof List<?> values) {
            return values.stream().map(element -> bind(name, element, type)).toList();
        }
        if (value == null || type == null || type == Object.class || type.isInstance(value)) {
            return value;
        }
        Class<?> boxed = BOXES.getOrDefault(type, type);
        try {
            return converted(value, boxed);
        } catch (RuntimeException unfit) {
            throw new IllegalArgumentException("parameter " + name + ": " + Json.write(value) + " is not a "
                    + boxed.getSimpleName(), unfit);
        }
    }

    private static Object converted(Object value, Class<?> type) {
        Function<BigDecimal, Object> number = NUMBERS.get(type);
        if (number != null && (value instanceof Number || value instanceof String)) {
            return number.apply(new BigDecimal(value.toString().strip()));
        }
        if (!(value instanceof String text)) {
            return value;
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(text)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("no constant " + text);
        }
        Function<String, Object> parser = TEXTS.get(type);
        return parser == null ? value : parser.apply(text.strip());
    }

    private static Boolean bool(String text) {
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("not true or false");
        }
        return Boolean.valueOf(text);
    }

    /** The type {@code target} gives its parameter {@code name}; {@code null} when it does not know it. */
    private static Class<?> declared(Query target, String name) {
        try {
            Parameter<?> parameter = target.getParameter(name);
            return parameter == null ? null : parameter.getParameterType();
        } catch (IllegalArgumentException | IllegalStateException _) {
            return null;
        }
    }

    private static Object parse(String name, String sent) throws Refused {
        try {
            return Json.parse(sent == null ? "" : sent);
        } catch (IllegalArgumentException unreadable) {
            throw new Refused(name + ": " + unreadable.getMessage());
        }
    }

    private static Map<?, ?> object(String name, Object value) throws Refused {
        if (value instanceof Map<?, ?> members) {
            return members;
        }
        throw new Refused(name + ": not a JSON object");
    }

    private static String text(Object value) throws Refused {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new Refused(QUERY_MEMBER + ": not a string");
    }

    private static Map<String, Object> params(Object given) throws Refused {
        Object value = given;
        if (value instanceof String text) {
            value = text.isBlank() ? null : parse(PARAMS_MEMBER, text);
        }
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> member : object(PARAMS_MEMBER, value).entrySet()) {
            Object parameter = member.getValue();
            boolean fits = isValue(parameter)
                    || (parameter instanceof List<?> values && values.stream().allMatch(Statement::isValue));
            if (!fits) {
                throw new Refused(PARAMS_MEMBER + "." + member.getKey() + ": not a value or a list of values");
            }
            out.put(String.valueOf(member.getKey()), parameter);
        }
        return Collections.unmodifiableMap(out);
    }

    private static boolean isValue(Object value) {
        return !(value instanceof Map<?, ?>) && !(value instanceof List<?>);
    }
}
