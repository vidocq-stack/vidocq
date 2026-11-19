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
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class MethodExpressionTest {
    final ExpressionFactory factory = ExpressionFactory.newInstance();

    @Test
    void invokesBareMethodExpressionsAndReportsActualMetadata() {
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("text", factory.createValueExpression("Peano", Object.class));
        Class<?>[] expected = {int.class, int.class};
        var method = factory.createMethodExpression(context, "#{text.substring}", String.class, expected);
        expected[0] = String.class;
        assertFalse(method.isParametersProvided());
        assertEquals("ea", method.invoke(context, new Object[] {1L, 3L}));
        var info = method.getMethodInfo(context);
        assertEquals("substring", info.getName());
        assertEquals(String.class, info.getReturnType());
        assertArrayEquals(new Class<?>[] {int.class, int.class}, info.getParamTypes());
        assertEquals("#{text.substring}", method.getExpressionString());
        assertFalse(method.isLiteralText());
    }

    @Test
    void inlineArgumentsOverrideInvokeParametersAndReferencesDoNotInvokeTheMethod() {
        var context = new StandardELContext(factory);
        var counter = new AtomicInteger(5);
        context.getVariableMapper().setVariable("counter", factory.createValueExpression(counter, Object.class));
        var method = factory.createMethodExpression(context, "#{counter.addAndGet(2)}", int.class, null);
        assertTrue(method.isParametersProvided());
        var reference = method.getMethodReference(context);
        assertSame(counter, reference.getBase());
        assertEquals("addAndGet", reference.getMethodInfo().getName());
        assertArrayEquals(new Object[] {2L}, reference.getEvaluatedParameters());
        assertEquals(5, counter.get());
        assertEquals(7, method.invoke(context, new Object[] {99}));
        assertEquals(7, counter.get());
    }

    @Test
    void validatesReturnTypesAndSupportsLiteralsAndMethodExpressionVariables() {
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("text", factory.createValueExpression("Peano", Object.class));
        var method = factory.createMethodExpression(context, "#{text.length}", int.class, new Class<?>[0]);
        context.getVariableMapper().setVariable("method", factory.createValueExpression(method, Object.class));
        var alias = factory.createMethodExpression(context, "#{method}", int.class, new Class<?>[0]);
        assertEquals(5, alias.invoke(context, null));
        assertEquals(int.class, alias.getMethodInfo(context).getReturnType());
        var literal = factory.createMethodExpression(context, "42", Long.class, new Class<?>[0]);
        assertTrue(literal.isLiteralText());
        assertEquals(42L, literal.invoke(context, new Object[] {"ignored"}));
        var voidLiteral = factory.createMethodExpression(context, "literal", void.class, new Class<?>[0]);
        assertTrue(voidLiteral.isLiteralText());
        assertEquals(void.class, voidLiteral.getMethodInfo(context).getReturnType());
        assertThrows(ELException.class, () -> voidLiteral.invoke(context, null));
        var mismatch = factory.createMethodExpression(context, "#{text.length}", String.class, new Class<?>[0]);
        assertThrows(MethodNotFoundException.class, () -> mismatch.getMethodInfo(context));
        assertThrows(MethodNotFoundException.class, () -> mismatch.invoke(context, null));
        assertThrows(NullPointerException.class, () -> factory.createMethodExpression(context, "#{text.length}", int.class, null));
        assertThrows(ELException.class, () -> factory.createMethodExpression(context, "#{text} #{text}", Object.class, new Class<?>[0]));
        context.getVariableMapper().setVariable("literal", factory.createValueExpression(literal, Object.class));
        var wrongAlias = factory.createMethodExpression(context, "#{literal}", String.class, new Class<?>[0]);
        assertThrows(MethodNotFoundException.class, () -> wrongAlias.invoke(context, null));
    }

    @Test
    void metadataFindsAccessibleInterfaceDeclarationsForHiddenImplementations() {
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("items", factory.createValueExpression(java.util.List.of(1,2), Object.class));
        var method = factory.createMethodExpression(context, "#{items.size}", int.class, new Class<?>[0]);
        assertEquals(int.class, method.getMethodInfo(context).getReturnType());
        assertEquals(2, method.invoke(context, null));
    }

    @Test
    void selectsVarargsFromInlineArgumentsAndIgnoresExpectedParameterTypes() {
        var context = new StandardELContext(factory);
        var method = factory.createMethodExpression(context, "#{String.format('%s:%s','x',2)}", String.class,
                new Class<?>[] {int.class});
        assertArrayEquals(new Class<?>[] {String.class, Object[].class}, method.getMethodInfo(context).getParamTypes());
        assertEquals("x:2", method.invoke(context, null));
    }
}

