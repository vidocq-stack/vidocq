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
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import org.junit.jupiter.api.Test;

class PeanoExpressionFactoryTest {
    @Test
    void bootstrapsTheProviderThroughTheJakartaELAPI() {
        var factory = ExpressionFactory.newInstance();
        assertEquals(PeanoExpressionFactory.class, factory.getClass());
        assertEquals("io.vidocq.peano.core", factory.getClass().getModule().getName());
        var context = new StandardELContext(factory);
        assertEquals(7L, factory.createValueExpression(context, "${1 + 2 * 3}", Object.class).<Object>getValue(context));
    }

    @Test
    void appliesExpectedTypesAndPreservesExpressionMetadata() {
        var factory = new PeanoExpressionFactory();
        var context = new StandardELContext(factory);
        var expression = factory.createValueExpression(context, "${42}", String.class);
        assertEquals("42", expression.getValue(context));
        assertEquals("${42}", expression.getExpressionString());
        assertEquals(String.class, expression.getExpectedType());
        assertEquals(Long.class, expression.getType(context));
        assertTrue(expression.isReadOnly(context));
        assertFalse(expression.isLiteralText());
        assertThrows(PropertyNotWritableException.class, () -> expression.setValue(context, 7));
        assertEquals(expression, factory.createValueExpression(context, "${42}", String.class));
        assertEquals(expression.hashCode(), factory.createValueExpression(context, "${42}", String.class).hashCode());
        assertNotEquals(expression, factory.createValueExpression(context, "${42}", Long.class));
    }

    @Test
    void distinguishesLiteralTextFromSingleEvalExpressionsAndComposites() {
        var factory = new PeanoExpressionFactory();
        var context = new StandardELContext(factory);
        assertTrue(factory.createValueExpression(context, "42", Long.class).isLiteralText());
        assertEquals(42L, factory.createValueExpression(context, "42", Long.class).<Object>getValue(context));
        var composite = factory.createValueExpression(context, "${4}${2}", Long.class);
        assertFalse(composite.isLiteralText());
        assertEquals(42L, composite.<Object>getValue(context));
        assertEquals(String.class, composite.getType(context));
        assertNull(factory.createValueExpression(context, "${null}", Long.class).getValue(context));
        assertEquals(0L, factory.createValueExpression(context, "${null}", long.class).<Object>getValue(context));
    }

    @Test
    void coercesObjectBackedExpressionsAndKeepsThemReadOnly() {
        var factory = new PeanoExpressionFactory();
        var context = new StandardELContext(factory);
        var expression = factory.createValueExpression("42", int.class);
        assertEquals(42, expression.<Object>getValue(context));
        assertEquals(String.class, expression.getType(context));
        assertTrue(expression.isLiteralText());
        assertThrows(PropertyNotWritableException.class, () -> expression.setValue(context, "1"));
        assertEquals("42", expression.getExpressionString());
        assertEquals(42L, factory.coerceToType("42", Long.class));
    }

    @Test
    void rejectsNullArgumentsAndMalformedSyntaxAtCreation() {
        var factory = new PeanoExpressionFactory();
        var context = new StandardELContext(factory);
        assertThrows(NullPointerException.class, () -> factory.createValueExpression(null, "${1}", Object.class));
        assertThrows(NullPointerException.class, () -> factory.createValueExpression(context, null, Object.class));
        assertThrows(NullPointerException.class, () -> factory.createValueExpression(context, "${1}", null));
        assertThrows(NullPointerException.class, () -> factory.createValueExpression(1, null));
        assertThrows(ELException.class, () -> factory.createValueExpression(context, "${1+}", Object.class));
        assertThrows(ELException.class, () -> factory.createMethodExpression(context, "#{1 + 2}", Object.class, new Class<?>[0]));
    }
}

