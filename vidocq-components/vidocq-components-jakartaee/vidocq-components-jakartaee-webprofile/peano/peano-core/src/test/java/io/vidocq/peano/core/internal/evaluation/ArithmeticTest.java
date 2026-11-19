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
import io.vidocq.peano.core.internal.syntax.TokenKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ArithmeticTest {
    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(TokenKind.PLUS, null, null, 0L),
                Arguments.of(TokenKind.PLUS, null, "2", 2L),
                Arguments.of(TokenKind.PLUS, "", "2", 2L),
                Arguments.of(TokenKind.PLUS, "1.5", 2L, 3.5d),
                Arguments.of(TokenKind.PLUS, BigInteger.TEN, 0.5d, new BigDecimal("10.5")),
                Arguments.of(TokenKind.PLUS, new BigDecimal("1.20"), new BigDecimal("2.0"), new BigDecimal("3.20")),
                Arguments.of(TokenKind.MINUS, BigInteger.TEN, 3L, BigInteger.valueOf(7)),
                Arguments.of(TokenKind.MULTIPLY, 3L, 4L, 12L),
                Arguments.of(TokenKind.DIVIDE, null, null, 0L),
                Arguments.of(TokenKind.DIVIDE, 5L, 2L, 2.5d),
                Arguments.of(TokenKind.DIVIDE, BigInteger.valueOf(5), BigInteger.valueOf(2), new BigDecimal("3")),
                Arguments.of(TokenKind.DIVIDE, new BigDecimal("5.00"), 2L, new BigDecimal("2.50")),
                Arguments.of(TokenKind.MODULO, null, null, 0L),
                Arguments.of(TokenKind.MODULO, BigInteger.TEN, 3L, BigInteger.ONE),
                Arguments.of(TokenKind.MODULO, new BigDecimal("5.5"), 2L, 1.5d),
                Arguments.of(TokenKind.MODULO, "5", 2L, 1L),
                Arguments.of(TokenKind.PLUS, Long.MAX_VALUE, 1L, Long.MIN_VALUE));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void choosesTheNumericDomainBeforeApplyingTheOperator(TokenKind operator, Object a, Object b, Object expected) {
        Object actual = Arithmetic.binary(operator, a, b);
        assertEquals(expected, actual);
        assertEquals(expected.getClass(), actual.getClass());
    }

    @Test
    void retainsTheNumericTypeForUnaryMinus() {
        Object[][] cases = {{null, 0L}, {(byte) 1, (byte) -1}, {(short) 1, (short) -1},
                {1, -1}, {1L, -1L}, {1f, -1f}, {1d, -1d},
                {BigInteger.ONE, BigInteger.ONE.negate()}, {BigDecimal.ONE, BigDecimal.ONE.negate()},
                {"1", -1L}, {"1e2", -100d}};
        for (Object[] row : cases) {
            Object actual = Arithmetic.negate(row[0]);
            assertEquals(row[1], actual);
            assertEquals(row[1].getClass(), actual.getClass());
        }
        assertThrows(ELException.class, () -> Arithmetic.negate('a'));
    }

    @Test
    void distinguishesFloatingPointZeroDivisionFromIntegerRemainderErrors() {
        assertEquals(Double.POSITIVE_INFINITY, Arithmetic.binary(TokenKind.DIVIDE, 1L, 0L));
        assertThrows(ELException.class, () -> Arithmetic.binary(TokenKind.MODULO, 1L, 0L));
        assertThrows(ELException.class, () -> Arithmetic.binary(TokenKind.DIVIDE, BigInteger.ONE, BigInteger.ZERO));
        assertThrows(ELException.class, () -> Arithmetic.binary(TokenKind.PLUS, true, 1L));
    }
}

