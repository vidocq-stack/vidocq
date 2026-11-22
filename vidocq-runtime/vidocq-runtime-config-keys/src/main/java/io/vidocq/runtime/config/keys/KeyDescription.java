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

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What is known of a configuration key: its pattern and the facts a file, or several, give about it.
 *
 * @param key   the pattern of the key
 * @param facts the fact kept for each attribute, with the file and the line it comes from; never
 *              {@link Attribute#NAMES}, which is a family's. In a {@link KeyCatalog}, the fact of
 *              {@link Attribute#VALUES} holds the values of every file and names the first that gave some
 */
public record KeyDescription(KeyPattern key, Map<Attribute, Fact> facts) {

    /** The type of a key whose description gives none. */
    public static final String STRING = "string";

    public KeyDescription {
        facts = facts.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(facts));
    }

    /**
     * The fact kept for an attribute.
     *
     * @param attribute the attribute
     * @return its value and where it is written, or empty when no file gives it
     */
    public Optional<Fact> fact(Attribute attribute) {
        return Optional.ofNullable(facts.get(attribute));
    }

    private Optional<String> value(Attribute attribute) {
        return fact(attribute).map(Fact::value);
    }

    /** The type of the value: one of the format's, {@code list:<type>}, or a qualified Java type name. */
    public String type() {
        return value(Attribute.TYPE).orElse(STRING);
    }

    /** The value the key takes when the application gives none. */
    public Optional<String> defaultValue() {
        return value(Attribute.DEFAULT);
    }

    /** Whether the application must give the key. */
    public boolean required() {
        return value(Attribute.REQUIRED).map(Boolean::parseBoolean).orElse(false);
    }

    /** The allowed values; empty when any value of the type is allowed. */
    public List<String> values() {
        return value(Attribute.VALUES).map(KeyDescription::list).orElse(List.of());
    }

    /** One line of documentation. */
    public Optional<String> doc() {
        return value(Attribute.DOC);
    }

    /** The sibling key and the values of it for which this key exists. */
    public Optional<Condition> when() {
        return value(Attribute.WHEN).flatMap(Condition::parse);
    }

    /** The family whose names the value takes. */
    public Optional<String> ref() {
        return value(Attribute.REF);
    }

    /** Whether the key should no longer be used. */
    public boolean deprecated() {
        return value(Attribute.DEPRECATED).map(Boolean::parseBoolean).orElse(false);
    }

    /** The class and the member that declare the key. */
    public Optional<String> source() {
        return value(Attribute.SOURCE);
    }

    /** The items of a list written with commas, trimmed, the empty ones left out. */
    static List<String> list(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    /**
     * A key that exists only for some values of a sibling key: {@code classpath} when {@code type} is
     * {@code static}.
     *
     * @param sibling the last segment of the sibling key
     * @param values  the values of the sibling for which the key exists
     */
    public record Condition(String sibling, List<String> values) {

        public Condition {
            values = List.copyOf(values);
        }

        /** Reads {@code <sibling>=<value>[,<value>]}; empty when the text is not a condition. */
        static Optional<Condition> parse(String text) {
            int equals = text.indexOf('=');
            if (equals <= 0) {
                return Optional.empty();
            }
            List<String> values = list(text.substring(equals + 1));
            String sibling = text.substring(0, equals).strip();
            if (sibling.isEmpty() || values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Condition(sibling, values));
        }
    }
}
