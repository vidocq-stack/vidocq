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
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class MethodCallsTest {
    final ExpressionFactory factory = ExpressionFactory.newInstance();

    Object eval(ELContext context, String body) {
        return factory.createValueExpression(context, "${" + body + "}", Object.class).getValue(context);
    }

    @Test
    void callsInstanceStaticImportedMethodsAndConstructorsThroughResolvers() {
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("text", factory.createValueExpression("Peano", Object.class));
        assertEquals("ea", eval(context, "text.substring('1',3)"));
        assertEquals("ea", eval(context, "text['substring'](1,3)"));
        assertEquals(2L, eval(context, "Math.max(1,2)"));
        context.getImportHandler().importStatic("java.lang.Math.abs");
        assertEquals(7L, eval(context, "abs(-7)"));
        assertEquals(5, eval(context, "String('Peano').length()"));
    }

    @Test
    void keepsCustomInvocationPrecedenceAndHonorsResolvedNullResults() {
        var context = new StandardELContext(factory);
        var target = new Object();
        context.getVariableMapper().setVariable("target", factory.createValueExpression(target, Object.class));
        context.addELResolver(new InvocationResolver(target));
        assertEquals(2, eval(context, "target.echo(1,2)"));
        assertNull(eval(context, "target.nil()"));
        context.setPropertyResolved(true);
        assertThrows(MethodNotFoundException.class, () -> eval(context, "target.missing()"));
        context.getVariableMapper().setVariable("nil", factory.createValueExpression(null, Object.class));
        assertNull(eval(context, "nil.echo(missing)"));
    }

    @Test
    void supportsStandaloneBeanFunctionAndAssignmentCallsThroughELProcessor() throws Exception {
        var processor = new ELProcessor();
        processor.defineBean("text", "Peano");
        processor.defineFunction("f", "max", Math.class.getMethod("max", int.class, int.class));
        assertEquals("ea", processor.<Object>eval("text.substring(1,3)"));
        assertEquals(3, processor.<Object>eval("f:max(2,3)"));
        assertEquals(84L, processor.<Object>eval("a = b = 42; a + b"));
    }

    static class InvocationResolver extends ELResolver {
        final Object target;

        InvocationResolver(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] args) {
            if (base == target && ("echo".equals(method) || "nil".equals(method))) {
                context.setPropertyResolved(base, method);
                return "nil".equals(method) ? null : args.length;
            }
            return null;
        }

        @Override
        public Object getValue(ELContext context, Object base, Object property) {
            return null;
        }

        @Override
        public Class<?> getType(ELContext context, Object base, Object property) {
            return null;
        }

        @Override
        public void setValue(ELContext context, Object base, Object property, Object value) {
            // This resolver only invokes methods: nothing to write.
        }

        @Override
        public boolean isReadOnly(ELContext context, Object base, Object property) {
            return false;
        }

        @Override
        public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return Object.class;
        }
    }
}

