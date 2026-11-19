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
package io.vidocq.peano.core.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class AssignmentExpressionTest {
    final ExpressionFactory factory = ExpressionFactory.newInstance();

    Object eval(ELContext context, String body) {
        return factory.createValueExpression(context, "${" + body + "}", Object.class).getValue(context);
    }

    @Test
    void assignsRightToLeftAndReturnsTheLastSequenceValue() {
        var context = new StandardELContext(factory);
        assertEquals(84L, eval(context, "a = b = 42; a + b"));
        assertEquals(42L, eval(context, "a"));
        assertEquals("Peano 6", eval(context, "name = 'Peano'; name += ' 6'"));
        assertEquals("Peano", eval(context, "name"));
    }

    @Test
    void freezesTheLeftHandReferenceBeforeEvaluatingTheRightHandSide() {
        var context = new StandardELContext(factory);
        var data = new HashMap<String, Object>();
        var calls = new ArrayList<String>();
        var helper = new Object();
        context.getVariableMapper().setVariable("data", factory.createValueExpression(data, Object.class));
        context.getVariableMapper().setVariable("helper", factory.createValueExpression(helper, Object.class));
        context.addELResolver(new MethodCallsTest.InvocationResolver(helper) {
            @Override
            public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] args) {
                if (base != helper) return null;
                context.setPropertyResolved(true);
                calls.add(method.toString());
                return "next".equals(method) ? "a" : 42L;
            }
        });
        assertEquals(42L, eval(context, "data[helper.next()] = helper.rhs()"));
        assertEquals(List.of("next", "rhs"), calls);
        assertEquals(42L, data.get("a"));
        calls.clear();
        context.getVariableMapper().setVariable("alias",
                factory.createValueExpression(context, "${data[helper.next()]}", Object.class));
        assertEquals(42L, eval(context, "alias = helper.rhs()"));
        assertEquals(List.of("next", "rhs"), calls);
    }

    @Test
    void rejectsNonLvaluesAndLambdaArguments() {
        var context = new StandardELContext(factory);
        assertThrows(PropertyNotWritableException.class, () -> eval(context, "1 = 2"));
        context.getVariableMapper().setVariable("fixed", factory.createValueExpression(1L, Object.class));
        assertThrows(PropertyNotWritableException.class, () -> eval(context, "fixed = 2"));
        context.enterLambdaScope(java.util.Map.of("x", 1L));
        try {
            assertThrows(PropertyNotWritableException.class, () -> eval(context, "x = 2"));
        } finally {
            context.exitLambdaScope();
        }
    }

    @Test
    void coercesWritesToTheActualPropertyTypeInsteadOfTheExpressionExpectedType() {
        var context = new StandardELContext(factory);
        var array = new Integer[] {1};
        context.getVariableMapper().setVariable("array", factory.createValueExpression(array, Object.class));
        factory.createValueExpression(context, "${array[0]}", String.class).setValue(context, "42");
        assertEquals(42, array[0]);
        var resolver = new ResolverValueExpressionTest.FixtureResolver();
        context.addELResolver(resolver);
        factory.createValueExpression(context, "${target.value}", Object.class).setValue(context, 7L);
        assertEquals("7", resolver.value);
    }
}

