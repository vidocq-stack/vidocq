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
package io.vidocq.runtime.config.keys;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The types of the format, and what a value has against one of them.
 *
 * <p>A value is read the way the runtime converts it: these are the conversions of
 * {@code io.vidocq.runtime.core.config.Converters}, the splitting of a list of {@code VidocqConfigImpl} and the
 * expressions of {@code PropertyExpressions}, copied because this module requires nothing.
 */
final class KeyTypes {

    /** What starts the type of a list: {@code list:int}. */
    static final String LIST = "list:";

    private static final String BOOLEAN = "boolean";
    private static final String INT = "int";
    private static final String LONG = "long";
    private static final String DOUBLE = "double";
    private static final String DURATION = "duration";

    /** The types of the format that are not lists. */
    private static final Set<String> OF_THE_FORMAT =
            Set.of(KeyDescription.STRING, BOOLEAN, INT, LONG, DOUBLE, DURATION);

    /** How the runtime reads a boolean, whatever the case. */
    private static final List<String> BOOLEANS = List.of("true", "yes", "y", "on", "1", "false", "no", "n", "off", "0");

    private KeyTypes() {}

    /** Whether a type is one of the format's own, a list apart: a Java type name is not. */
    static boolean ofTheFormat(String type) {
        return OF_THE_FORMAT.contains(type);
    }

    /** Whether a type is a list. */
    static boolean list(String type) {
        return type.startsWith(LIST);
    }

    /** The type of the items of a list; the type itself when it is not a list. */
    static String item(String type) {
        return list(type) ? type.substring(LIST.length()) : type;
    }

    /**
     * Why a value is not of a type of the format.
     *
     * @param type  a type that is not a list
     * @param value the value, stripped and not empty
     * @return what is wrong, to be read after the value; empty when the value is of the type, or when it cannot
     *         be told: a string takes any value, and a Java type is the application's to convert
     */
    static Optional<String> refusal(String type, String value) {
        return switch (type) {
            case BOOLEAN -> BOOLEANS.contains(value.toLowerCase(Locale.ROOT)) ? Optional.empty()
                    : Optional.of("is not a boolean (" + String.join(", ", BOOLEANS) + ")");
            case INT -> unless(Integer::parseInt, value, "is not an int");
            case LONG -> unless(Long::parseLong, value, "is not a long");
            case DOUBLE -> unless(Double::parseDouble, value, "is not a double");
            case DURATION -> unless(Duration::parse, value, "is not a duration, written like PT30S");
            default -> Optional.empty();
        };
    }

    private static Optional<String> unless(Function<String, Object> conversion, String value, String refusal) {
        try {
            conversion.apply(value);
            return Optional.empty();
        } catch (NumberFormatException | DateTimeParseException _) {
            return Optional.of(refusal);
        }
    }

    /**
     * Whether a value holds a property expression, {@code ${...}}, which only the runtime can expand; a dollar
     * after a backslash is a dollar.
     */
    static boolean holdsExpression(String value) {
        int at = 0;
        while (at < value.length()) {
            if (value.startsWith("\\$", at)) {
                at += 2;
            } else if (value.startsWith("${", at)) {
                return true;
            } else {
                at++;
            }
        }
        return false;
    }

    /**
     * The items of a list an application writes: split on the commas a backslash does not escape, each item
     * stripped, the empty ones left out.
     */
    static List<String> items(String value) {
        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                item.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ',') {
                add(items, item);
            } else {
                item.append(c);
            }
        }
        add(items, item);
        return items;
    }

    private static void add(List<String> items, StringBuilder item) {
        String text = item.toString().strip();
        if (!text.isEmpty()) {
            items.add(text);
        }
        item.setLength(0);
    }
}
