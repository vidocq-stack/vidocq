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
package io.vidocq.kerckhoffs.core.internal.expression;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The {@code *Expression} attributes of the security annotations are Jakarta Expression Language (section 3.8). */
class ExpressionEvaluatorTest {

    /** What an application bean looks like to an expression: {@code self.isRememberMe(httpMessageContext)}. */
    public static final class Mechanism {
        public Boolean isRememberMe(Object context) {
            return "ctx".equals(context);
        }
    }

    @Test
    void anImmediateExpressionIsEvaluated() {
        assertEquals(2, ExpressionEvaluator.evaluate("${1 + 1}", Integer.class, Map.of()));
    }

    @Test
    void aDeferredExpressionSeesTheVariablesOfTheCall() {
        assertEquals("reza", ExpressionEvaluator.evaluate("#{caller}", String.class, Map.of("caller", "reza")));
    }

    @Test
    void aMethodOfTheMechanismIsCalledWithTheContext() {
        var variables = Map.<String, Object>of("self", new Mechanism(), "httpMessageContext", "ctx");

        assertEquals(true, ExpressionEvaluator.evaluate("#{self.isRememberMe(httpMessageContext)}", Boolean.class,
                variables));
    }

    @Test
    void aTextWithoutDelimitersIsItsOwnValue() {
        assertEquals("plain", ExpressionEvaluator.evaluate("plain", String.class, Map.of()));
    }

    @Test
    void anExpressionThatCannotBeEvaluatedIsRefusedNotIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate("#{1 +}", Boolean.class, Map.of()));
    }
}
