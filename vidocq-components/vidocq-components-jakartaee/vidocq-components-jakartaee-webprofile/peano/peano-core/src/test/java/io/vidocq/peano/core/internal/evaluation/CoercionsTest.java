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
package io.vidocq.peano.core.internal.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;
import jakarta.el.ELException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CoercionsTest {
    enum Color {
        RED;
        @Override
        public String toString() {
            return "custom";
        }
    }

    static Stream<Arguments> scalarCases() {
        return Stream.of(
                Arguments.of(null, String.class, ""),
                Arguments.of(null, Object.class, null),
                Arguments.of(null, Long.class, null),
                Arguments.of(null, Boolean.class, null),
                Arguments.of(null, Character.class, null),
                Arguments.of(null, int.class, 0),
                Arguments.of(null, long.class, 0L),
                Arguments.of(null, byte.class, (byte) 0),
                Arguments.of(null, short.class, (short) 0),
                Arguments.of(null, float.class, 0f),
                Arguments.of(null, double.class, 0d),
                Arguments.of(null, boolean.class, false),
                Arguments.of(null, char.class, (char) 0),
                Arguments.of("", int.class, 0),
                Arguments.of("", Long.class, 0L),
                Arguments.of("", Boolean.class, false),
                Arguments.of("", Character.class, (char) 0),
                Arguments.of("TrUe", boolean.class, true),
                Arguments.of("anything", boolean.class, false),
                Arguments.of("42", long.class, 42L),
                Arguments.of("127", Byte.class, (byte) 127),
                Arguments.of("42", Short.class, (short) 42),
                Arguments.of("42", Integer.class, 42),
                Arguments.of("3.5", Double.class, 3.5d),
                Arguments.of("1.25", Float.class, 1.25f),
                Arguments.of("42", BigInteger.class, new BigInteger("42")),
                Arguments.of("1.20", BigDecimal.class, new BigDecimal("1.20")),
                Arguments.of(258L, Byte.class, (byte) 2),
                Arguments.of(3.9d, Long.class, 3L),
                Arguments.of(new BigDecimal("3.9"), BigInteger.class, BigInteger.valueOf(3)),
                Arguments.of(BigInteger.TEN, BigDecimal.class, new BigDecimal(BigInteger.TEN)),
                Arguments.of(0.1d, BigDecimal.class, new BigDecimal(0.1d)),
                Arguments.of('A', Long.class, 65L),
                Arguments.of('\uffff', Long.class, -1L),
                Arguments.of(65L, Character.class, 'A'),
                Arguments.of("abc", Character.class, 'a'),
                Arguments.of(Color.RED, String.class, "RED"),
                Arguments.of("RED", Color.class, Color.RED),
                Arguments.of("", Color.class, null),
                Arguments.of("", Object.class, ""),
                Arguments.of("", java.util.Date.class, null));
    }

    @ParameterizedTest
    @MethodSource("scalarCases")
    void appliesTheScalarConversionTable(Object input, Class<?> type, Object expected) {
        Object actual = Coercions.convert(input, type);
        assertEquals(expected, actual);
        if (expected != null) {
            assertEquals(expected.getClass(), actual.getClass());
        }
    }

    @Test
    void rejectsInvalidConversionsAsELExceptions() {
        Object[][] cases = {
                {true, Integer.class}, {1L, Boolean.class}, {true, Character.class},
                {"128", Byte.class}, {"1.5", Long.class}, {"bad", Double.class},
                {"BLUE", Color.class}, {Color.RED, Integer.class},
                {"nonempty", java.util.Date.class}
        };
        for (Object[] entry : cases) {
            assertThrows(ELException.class, () -> Coercions.convert(entry[0], (Class<?>) entry[1]));
        }
    }

    @Test
    void keepsAssignableObjectsAndWrapsFailedStringConversions() {
        var value = new Object();
        assertSame(value, Coercions.convert(value, Object.class));
        var broken = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("broken");
            }
        };
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ELException.class, () -> Coercions.convert(broken, String.class)).getCause());
    }

    @Test
    void convertsArrayElementsIncludingPrimitiveAndNestedArrays() {
        assertArrayEquals(new int[] {1, 2, 0}, Coercions.convert(new String[] {"1", "2", null}, int[].class));
        assertArrayEquals(new String[] {"1", "2"}, Coercions.convert(new int[] {1, 2}, String[].class));
        assertArrayEquals(new int[][] {{1, 2}}, Coercions.convert(new String[][] {{"1", "2"}}, int[][].class));
        var array = new int[] {1};
        assertSame(array, Coercions.convert(array, int[].class));
        assertThrows(ELException.class, () -> Coercions.convert(new String[] {"bad"}, int[].class));
    }
}

