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

import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("BuiltInConverters — Converter contract of every built-in converter (MP Config 3.1 §5.1)")
class BuiltInConvertersContractTest {

    /** Converters that never reject a non-empty input (or whose rejection needs a lookup: DNS, class loading). */
    private static final Set<Class<?>> ACCEPT_ANY_INPUT = Set.of(
            String.class, Boolean.class, boolean.class, Class.class, InetAddress.class);

    @Test
    void every_converter_but_the_String_identity_rejects_null_with_a_NullPointerException() {
        // Converter#convert: passing null "may" throw a NullPointerException — the String identity
        // converter returns its input unchanged instead.
        for (Map.Entry<Class<?>, Converter<?>> entry : BuiltInConverters.all().entrySet()) {
            if (entry.getKey() == String.class) {
                continue;
            }
            Converter<?> converter = entry.getValue();
            assertThrows(NullPointerException.class, () -> converter.convert(null), entry.getKey().getName());
        }
    }

    @Test
    void empty_input_converts_to_null_or_to_an_empty_primitive_optional() {
        Map<Class<?>, Object> emptyResults = Map.of(
                OptionalInt.class, OptionalInt.empty(),
                OptionalLong.class, OptionalLong.empty(),
                OptionalDouble.class, OptionalDouble.empty());
        for (Map.Entry<Class<?>, Converter<?>> entry : BuiltInConverters.all().entrySet()) {
            Class<?> type = entry.getKey();
            if (type == String.class) {
                continue;
            }
            Object converted = entry.getValue().convert("");
            if (emptyResults.containsKey(type)) {
                assertEquals(emptyResults.get(type), converted, type.getName());
            } else {
                assertNull(converted, type.getName());
            }
        }
    }

    @Test
    void blank_boolean_converts_to_null() {
        assertNull(new BuiltInConverters.BooleanConverter().convert("   "));
        assertFalse(new BuiltInConverters.BooleanConverter().convert("off"));
    }

    @Test
    void malformed_input_is_rejected_with_an_IllegalArgumentException() {
        for (Map.Entry<Class<?>, Converter<?>> entry : BuiltInConverters.all().entrySet()) {
            if (ACCEPT_ANY_INPUT.contains(entry.getKey())) {
                continue;
            }
            Converter<?> converter = entry.getValue();
            assertThrows(IllegalArgumentException.class, () -> converter.convert("not valid ::"),
                    entry.getKey().getName());
        }
    }
}
