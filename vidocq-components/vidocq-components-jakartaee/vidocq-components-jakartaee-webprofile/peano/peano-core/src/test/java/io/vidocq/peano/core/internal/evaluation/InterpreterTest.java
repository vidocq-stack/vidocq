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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jakarta.el.ELException;
import io.vidocq.peano.core.internal.syntax.ExpressionParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InterpreterTest {
    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("${1 + 2 * 3}", 7L),
                Arguments.of("${(1 + 2) * 3}", 9L),
                Arguments.of("${7 div 2}", 3.5d),
                Arguments.of("${7 mod 3}", 1L),
                Arguments.of("${'2' + 3}", 5L),
                Arguments.of("${null + 2}", 2L),
                Arguments.of("${null}", null),
                Arguments.of("${-null}", 0L),
                Arguments.of("${1.5e2}", 150d),
                Arguments.of("${9223372036854775807}", Long.MAX_VALUE),
                Arguments.of("${-9223372036854775808}", Long.MIN_VALUE),
                Arguments.of("${false and missing}", false),
                Arguments.of("${true or missing}", true),
                Arguments.of("${false ? missing : 42}", 42L),
                Arguments.of("${true ? 42 : missing}", 42L),
                Arguments.of("${not 'true'}", false),
                Arguments.of("${empty null}", true),
                Arguments.of("${empty ''}", true),
                Arguments.of("${empty 'x'}", false),
                Arguments.of("${1 lt 2 and 2 == '2'}", true),
                Arguments.of("${null == ''}", false),
                Arguments.of("${1 ne 2}", true),
                Arguments.of("${'Peano ' += 6}", "Peano 6"),
                Arguments.of("value=${2+3}; null=${null}", "value=5; null="),
                Arguments.of("#{true ? 1 : 2}", 1L),
                Arguments.of("1 + 2", "1 + 2"));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void evaluatesLiteralOperatorsAndTemplates(String source, Object expected) {
        Object actual = Interpreter.evaluate(ExpressionParser.parse(source));
        assertEquals(expected, actual);
        if (expected != null) assertEquals(expected.getClass(), actual.getClass());
    }

    @Test
    void doesNotSilentlyEvaluateUnimplementedNodesOrOutOfRangeIntegers() {
        for (String source : List.of("${missing}", "${bean.name}", "${f()}",
                "${x=1}", "${x->x}", "${9223372036854775808}")) {
            var template = ExpressionParser.parse(source);
            assertThrows(ELException.class, () -> Interpreter.evaluate(template), source);
        }
        var remainder = ExpressionParser.parse("${1 mod 0}");
        assertThrows(ELException.class, () -> Interpreter.evaluate(remainder));
    }

    @Test
    void recognizesAllEmptyKindsWithoutTreatingZeroOrFalseAsEmpty() {
        for (Object value : new Object[] {null, "", List.of(), Map.of(), new int[0], new Object[0]}) {
            assertTrue(Interpreter.isEmpty(value));
        }
        for (Object value : new Object[] {0L, false, "x", List.of(1), Map.of("x", 1), new int[] {1}}) {
            assertFalse(Interpreter.isEmpty(value));
        }
    }
}

