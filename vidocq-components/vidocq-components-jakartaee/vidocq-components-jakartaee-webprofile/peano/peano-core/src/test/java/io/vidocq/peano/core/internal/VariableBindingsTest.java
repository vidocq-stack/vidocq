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
import java.util.HashMap;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class VariableBindingsTest {
    final ExpressionFactory factory = ExpressionFactory.newInstance();

    @Test
    void capturesVariableMappingsAtCreationAndUsesTheEvaluationContext() {
        var creation = new StandardELContext(factory);
        creation.getVariableMapper().setVariable("v", factory.createValueExpression(1L, Object.class));
        var old = factory.createValueExpression(creation, "${v + v}", Object.class);
        creation.getVariableMapper().setVariable("v", factory.createValueExpression(2L, Object.class));
        var fresh = factory.createValueExpression(creation, "${v + v}", Object.class);
        var evaluation = new StandardELContext(factory);
        assertEquals(2L, old.<Object>getValue(evaluation));
        assertEquals(4L, fresh.<Object>getValue(evaluation));
        assertNotEquals(old, fresh);
    }

    @Test
    void delegatesMutableAliasesToTheirCapturedExpressions() {
        var data = new HashMap<String, Object>();
        data.put("name", "old");
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("data", factory.createValueExpression(data, Object.class));
        var mapped = factory.createValueExpression(context, "${data.name}", Object.class);
        context.getVariableMapper().setVariable("alias", mapped);
        var alias = factory.createValueExpression(context, "${alias}", Object.class);
        assertEquals(Object.class, alias.getType(context));
        assertFalse(alias.isReadOnly(context));
        alias.setValue(context, "new");
        assertEquals("new", data.get("name"));
        assertSame(data, alias.getValueReference(context).getBase());
    }

    @Test
    void doesNotAdoptMappingsAddedAfterCreation() {
        var context = new StandardELContext(factory);
        var old = factory.createValueExpression(context, "${late}", Object.class);
        context.getVariableMapper().setVariable("late", factory.createValueExpression(42L, Object.class));
        assertThrows(PropertyNotFoundException.class, () -> old.getValue(context));
    }

    @Test
    void lambdaScopeArgumentsShadowCapturedVariablesAndRemainReadOnly() {
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("x", factory.createValueExpression(1L, Object.class));
        var expression = factory.createValueExpression(context, "${x}", Object.class);
        context.enterLambdaScope(java.util.Map.of("x", 42L));
        try {
            assertEquals(42L, expression.<Object>getValue(context));
            assertTrue(expression.isReadOnly(context));
            assertThrows(PropertyNotWritableException.class, () -> expression.setValue(context, 7L));
        } finally {
            context.exitLambdaScope();
        }
        assertEquals(1L, expression.<Object>getValue(context));
    }

    @Test
    void supportsContextsWithVariableMappingDisabled() {
        var standard = new StandardELContext(factory);
        var context = new ELContext() {
            @Override
            public ELResolver getELResolver() {
                return standard.getELResolver();
            }

            @Override
            public FunctionMapper getFunctionMapper() {
                return null;
            }

            @Override
            public VariableMapper getVariableMapper() {
                return null;
            }
        };
        var expression = factory.createValueExpression(context, "${value}", Object.class);
        expression.setValue(context, 42L);
        assertEquals(42L, expression.<Object>getValue(context));
    }
}

