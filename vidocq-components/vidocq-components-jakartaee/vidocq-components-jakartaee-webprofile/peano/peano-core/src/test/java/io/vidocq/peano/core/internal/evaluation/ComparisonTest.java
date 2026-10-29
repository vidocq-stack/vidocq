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
import jakarta.el.ELException;
import io.vidocq.peano.core.internal.syntax.TokenKind;
import org.junit.jupiter.api.Test;

class ComparisonTest {
    @Test
    void distinguishesDecimalScaleEqualityFromOrdering() {
        assertFalse(Comparison.isEqual(new BigDecimal("1.0"), new BigDecimal("1.00")));
        assertTrue(Comparison.relate(TokenKind.LESS_EQUAL, new BigDecimal("1.0"), new BigDecimal("1.00")));
    }

    @Test
    void comparesNumericStringsNullsEnumsAndBooleansInTheSpecifiedOrder() {
        assertTrue(Comparison.isEqual(1L, "1"));
        assertFalse(Comparison.isEqual("01", "1"));
        assertTrue(Comparison.isEqual(BigInteger.ONE, 1L));
        assertTrue(Comparison.isEqual(true, "TRUE"));
        assertTrue(Comparison.isEqual(CoercionsTest.Color.RED, "RED"));
        assertFalse(Comparison.isEqual(CoercionsTest.Color.RED, ""));
        assertFalse(Comparison.isEqual("", CoercionsTest.Color.RED));
        assertFalse(Comparison.isEqual(null, ""));
        assertTrue(Comparison.isEqual(null, null));
        assertTrue(Comparison.relate(TokenKind.LESS, "10", "2"));
        assertFalse(Comparison.relate(TokenKind.LESS, null, 1L));
        assertTrue(Comparison.relate(TokenKind.LESS_EQUAL, null, null));
        assertFalse(Comparison.relate(TokenKind.GREATER, null, null));
    }

    @Test
    void preservesIEEEEqualityAndOrderingForNaNAndSignedZero() {
        assertFalse(Comparison.isEqual(Double.NaN, Double.NaN));
        assertTrue(Comparison.isEqual(-0d, 0d));
        assertFalse(Comparison.relate(TokenKind.LESS, Double.NaN, 1d));
        assertFalse(Comparison.relate(TokenKind.GREATER_EQUAL, Double.NaN, 1d));
        assertTrue(Comparison.relate(TokenKind.LESS_EQUAL, -0d, 0d));
    }

    @Test
    void usesComparableFromEitherSideAndWrapsErrors() {
        class Rank implements Comparable<Object> {
            @Override
            public int compareTo(Object other) {
                return -1;
            }
        }
        assertTrue(Comparison.relate(TokenKind.LESS, new Rank(), new Object()));
        assertTrue(Comparison.relate(TokenKind.GREATER, new Object(), new Rank()));
        var left = new Object();
        var right = new Object();
        assertThrows(ELException.class, () -> Comparison.relate(TokenKind.LESS, left, right));
    }
}

