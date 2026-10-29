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
import jakarta.el.*;
import io.vidocq.peano.spi.CompiledMethodExpression;
import org.junit.jupiter.api.Test;

/** The compiled method protocol, run in-process with hand-written emitted-class doubles. */
class CompiledMethodExpressionTest {
    private static final String REFLECTION = "peano.reflection";
    private static final Class<?>[] NO_TYPES = {};

    @Test
    void literalMethodsConvertTheirTextAndRejectVoid() {
        var context = context();
        var literal = new Fixture("hello", null, NO_TYPES, 0, "hello");
        assertEquals("hello", literal.invoke(context, null));
        assertTrue(literal.isLiteralText());
        assertFalse(literal.isParametersProvided());
        assertEquals("hello", literal.getExpressionString());
        assertEquals(String.class, literal.getMethodInfo(context).getReturnType());
        assertNull(literal.getMethodReference(context));
        assertEquals(42, new Fixture("42", Integer.class, NO_TYPES, 0, "42").invoke(context, null));
        var empty = new Object[0];
        var voidLiteral = new Fixture("x", void.class, NO_TYPES, 0, "x");
        assertThrows(ELException.class, () -> voidLiteral.invoke(context, empty));
    }

    @Test
    void delegatesFollowCompiledAndForeignMethodExpressions() {
        var context = context();
        var compiled = new Fixture("#{m}", String.class, NO_TYPES, 1, new Fixture("hello", null, NO_TYPES, 0, "hello"));
        assertEquals("hello", compiled.invoke(context, null));
        assertEquals(String.class, compiled.getMethodInfo(context).getReturnType());
        assertNull(compiled.getMethodReference(context));
        MethodExpression foreign = ExpressionFactory.newInstance()
                .createMethodExpression(context, "text", String.class, NO_TYPES);
        assertEquals("text", new Fixture("#{m}", null, NO_TYPES, 1, foreign).invoke(context, null));
        var mismatch = new Fixture("#{m}", Integer.class, NO_TYPES, 1, foreign);
        assertThrows(MethodNotFoundException.class, () -> mismatch.invoke(context, null));
        var notAMethod = new Fixture("#{m}", null, NO_TYPES, 1, "plain value");
        assertThrows(MethodNotFoundException.class, () -> notAMethod.invoke(context, null));
    }

    @Test
    void resolvedMethodsInvokeThroughTheContextResolverAndReportMetadata() {
        var context = context();
        var concat = new Fixture("#{s.concat}", String.class, new Class<?>[]{String.class}, 2, "abc", "concat");
        assertEquals("abcd", concat.invoke(context, new Object[]{"d"}));
        assertEquals("concat", concat.getMethodInfo(context).getName());
        var reference = concat.getMethodReference(context);
        assertEquals("abc", reference.getBase());
        assertEquals(String.class, reference.getMethodInfo().getReturnType());
        var inline = new Fixture("#{s.concat('d')}", null, null, 3, "abc", "concat", new Object[]{"d"});
        assertTrue(inline.isParametersProvided());
        assertEquals("abcd", inline.invoke(context, null));
        var abs = new Fixture("#{Math.abs}", null, new Class<?>[]{int.class}, 2, new ELClass(Math.class), "abs");
        assertEquals(3, abs.invoke(context, new Object[]{-3}));
        assertEquals(int.class, abs.getMethodInfo(context).getReturnType());
        var wrongReturn = new Fixture("#{s.concat}", Integer.class, new Class<?>[]{String.class}, 2, "abc", "concat");
        Object[] arguments = {"d"};
        assertThrows(MethodNotFoundException.class, () -> wrongReturn.invoke(context, arguments));
        var nullBase = new Fixture("#{s.m}", null, NO_TYPES, 2, null, "m");
        assertThrows(PropertyNotFoundException.class, () -> nullBase.invoke(context, null));
        var nullProperty = new Fixture("#{s.m}", null, NO_TYPES, 2, "abc", null);
        assertThrows(PropertyNotFoundException.class, () -> nullProperty.invoke(context, null));
    }

    @Test
    void unresolvedCallsFallBackToReflectionOnlyWhenAllowed() {
        var bare = new BareContext();
        var concat = new Fixture("#{s.concat}", null, new Class<?>[]{String.class}, 2, "abc", "concat");
        Object[] arguments = {"d"};
        String previous = System.getProperty(REFLECTION);
        try {
            System.setProperty(REFLECTION, "allow");
            assertEquals("abcd", concat.invoke(bare, arguments));
            System.setProperty(REFLECTION, "deny");
            assertThrows(ELException.class, () -> concat.invoke(bare, arguments));
            System.setProperty(REFLECTION, "sometimes");
            assertThrows(ELException.class, () -> concat.invoke(bare, arguments));
        } finally {
            if (previous == null) System.clearProperty(REFLECTION);
            else System.setProperty(REFLECTION, previous);
        }
        var inline = new Fixture("#{s.concat('d')}", null, null, 3, "abc", "concat", new Object[]{"d"});
        assertThrows(MethodNotFoundException.class, () -> inline.invoke(bare, null));
    }

    @Test
    void equalityFollowsSourceIdentityReturnAndParameterTypes() {
        var first = new Fixture("#{s.concat}", String.class, new Class<?>[]{String.class}, 2, "abc", "concat");
        var second = new Fixture("#{s.concat}", String.class, new Class<?>[]{String.class}, 2, "xyz", "concat");
        var other = new Fixture("#{s.concat}", String.class, new Class<?>[]{Object.class}, 2, "abc", "concat");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
    }

    private static StandardELContext context() {
        return new StandardELContext(ExpressionFactory.newInstance());
    }

    /** An emitted method expression class: part i is the i-th fixed value. */
    private static final class Fixture extends CompiledMethodExpression {
        private final transient Object[] parts;

        Fixture(String source, Class<?> expectedReturn, Class<?>[] types, int shape, Object... parts) {
            super(source, source, null, expectedReturn, types, shape, new String[0], new String[0]);
            this.parts = parts;
        }

        @Override
        protected Object evaluatePart(ELContext context, int part) {
            return parts[part];
        }
    }

    /** A context whose resolver resolves nothing. */
    private static final class BareContext extends ELContext {
        private final ELResolver resolver = new CompositeELResolver();

        @Override
        public ELResolver getELResolver() {
            return resolver;
        }

        @Override
        public FunctionMapper getFunctionMapper() {
            return null;
        }

        @Override
        public VariableMapper getVariableMapper() {
            return null;
        }
    }
}
