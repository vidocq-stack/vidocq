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
 * @param key        the pattern of the key
 * @param attributes the facts, as written; never {@link Attribute#NAMES}, which is a family's
 */
public record KeyDescription(KeyPattern key, Map<Attribute, String> attributes) {

    /** The type of a key whose description gives none. */
    public static final String STRING = "string";

    public KeyDescription {
        attributes = attributes.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(attributes));
    }

    /** The type of the value: one of the format's, {@code list:<type>}, or a qualified Java type name. */
    public String type() {
        return attributes.getOrDefault(Attribute.TYPE, STRING);
    }

    /** The value the key takes when the application gives none. */
    public Optional<String> defaultValue() {
        return Optional.ofNullable(attributes.get(Attribute.DEFAULT));
    }

    /** Whether the application must give the key. */
    public boolean required() {
        return Boolean.parseBoolean(attributes.get(Attribute.REQUIRED));
    }

    /** The allowed values; empty when any value of the type is allowed. */
    public List<String> values() {
        return list(attributes.get(Attribute.VALUES));
    }

    /** One line of documentation. */
    public Optional<String> doc() {
        return Optional.ofNullable(attributes.get(Attribute.DOC));
    }

    /** The sibling key and the values of it for which this key exists. */
    public Optional<Condition> when() {
        return Optional.ofNullable(attributes.get(Attribute.WHEN)).map(Condition::parse);
    }

    /** The family whose names the value takes. */
    public Optional<String> ref() {
        return Optional.ofNullable(attributes.get(Attribute.REF));
    }

    /** Whether the key should no longer be used. */
    public boolean deprecated() {
        return Boolean.parseBoolean(attributes.get(Attribute.DEPRECATED));
    }

    /** The class and the member that declare the key. */
    public Optional<String> source() {
        return Optional.ofNullable(attributes.get(Attribute.SOURCE));
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

        /** Reads {@code <sibling>=<value>[,<value>]}; the text was checked when the file was read. */
        static Condition parse(String text) {
            int equals = text.indexOf('=');
            return new Condition(text.substring(0, equals).strip(), list(text.substring(equals + 1)));
        }
    }
}
