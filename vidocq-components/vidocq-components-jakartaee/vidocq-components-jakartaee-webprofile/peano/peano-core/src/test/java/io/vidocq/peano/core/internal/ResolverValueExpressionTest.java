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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class ResolverValueExpressionTest {
    final ExpressionFactory factory = ExpressionFactory.newInstance();

    StandardELContext context(String name, Object value) {
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable(name, factory.createValueExpression(value, Object.class));
        return context;
    }

    ValueExpression expression(ELContext context, String body) {
        return factory.createValueExpression(context, "${" + body + "}", Object.class);
    }

    @Test
    void navigatesMapsListsArraysAndExportedBeansThroughTheStandardChain() {
        var data = new HashMap<String, Object>();
        data.put("rows", new ArrayList<>(List.of(Map.of("price", 42L))));
        data.put("array", new int[] {7, 8});
        data.put("counter", new AtomicInteger(3));
        var context = context("data", data);
        assertEquals(42L, expression(context, "data.rows[0]['price']").<Object>getValue(context));
        assertEquals(8, expression(context, "data.array[1]").<Object>getValue(context));
        assertEquals(int.class, expression(context, "data.array[1]").getType(context));
        assertEquals(3, expression(context, "data.counter.plain").<Object>getValue(context));
        assertEquals(int.class, expression(context, "data.counter.plain").getType(context));
        assertEquals(Object.class, expression(context, "data.rows[0]").getType(context));
    }

    @Test
    void writesPropertiesAndReportsTheirMetadataAndReferences() {
        var data = new HashMap<String, Object>();
        data.put("array", new Integer[] {7});
        data.put("rows", new ArrayList<>(List.of("old")));
        var context = context("data", data);
        var property = expression(context, "data.name");
        assertFalse(property.isReadOnly(context));
        property.setValue(context, "Peano");
        assertEquals("Peano", data.get("name"));
        var reference = property.getValueReference(context);
        assertSame(data, reference.getBase());
        assertEquals("name", reference.getProperty());
        expression(context, "data.array[0]").setValue(context, 42);
        assertEquals(42, ((Integer[]) data.get("array"))[0]);
        expression(context, "data.rows[0]").setValue(context, "new");
        assertEquals("new", ((List<?>) data.get("rows")).getFirst());
        var sum = expression(context, "1+2");
        assertThrows(PropertyNotWritableException.class, () -> sum.setValue(context, 4));
    }

    @Test
    void resolvesNullValuesAndNullNavigationWithoutReadingSkippedKeys() {
        var context = context("nil", null);
        assertNull(expression(context, "nil").getValue(context));
        assertNull(expression(context, "nil[missing].name").getValue(context));
        var map = new HashMap<String, Object>();
        context.getVariableMapper().setVariable("map", factory.createValueExpression(map, Object.class));
        assertNull(expression(context, "map.missing.name").getValue(context));
        assertNull(expression(context, "map[null]").getValue(context));
        for (String body : List.of("nil.name", "map[null]", "map.missing.name")) {
            var expr = expression(context, body);
            assertThrows(PropertyNotFoundException.class, () -> expr.getType(context));
            assertThrows(PropertyNotFoundException.class, () -> expr.isReadOnly(context));
            assertThrows(PropertyNotFoundException.class, () -> expr.setValue(context, 1));
        }
    }

    @Test
    void reportsUnresolvedIdentifiersAndResetsStaleResolutionFlags() {
        var context = new StandardELContext(factory);
        var expr = expression(context, "missing");
        context.setPropertyResolved(true);
        assertThrows(PropertyNotFoundException.class, () -> expr.getValue(context));
        assertThrows(PropertyNotFoundException.class, () -> expr.getType(context));
        expr.setValue(context, 42L);
        assertEquals(42L, expr.<Object>getValue(context));
    }

    @Test
    void customResolversTakePrecedenceAndMetadataDoesNotReadTheFinalValue() {
        var context = new StandardELContext(factory);
        var resolver = new FixtureResolver();
        context.addELResolver(resolver);
        var expr = expression(context, "target.value");
        resolver.forbidValueRead = true;
        assertEquals(String.class, expr.getType(context));
        assertFalse(expr.isReadOnly(context));
        var reference = expr.getValueReference(context);
        assertSame(resolver.target, reference.getBase());
        assertEquals("value", reference.getProperty());
        expr.setValue(context, "changed");
        assertEquals("changed", resolver.value);
        assertEquals(0, resolver.valueReads);
        resolver.forbidValueRead = false;
        assertEquals("changed", expr.getValue(context));
        assertEquals(1, resolver.valueReads);
        context.setPropertyResolved(true);
        assertNull(expression(context, "claimedNull").getValue(context));
    }

    static final class FixtureResolver extends ELResolver {
        final Object target = new Object();
        Object value = "original";
        boolean forbidValueRead;
        int valueReads;

        @Override
        public Object getValue(ELContext context, Object base, Object property) {
            if (base == null && ("target".equals(property) || "claimedNull".equals(property))) {
                context.setPropertyResolved(base, property);
                return "target".equals(property) ? target : null;
            }
            if (base == target && "value".equals(property)) {
                if (forbidValueRead) throw new AssertionError("Metadata read the final value");
                valueReads++;
                context.setPropertyResolved(base, property);
                return value;
            }
            return null;
        }

        @Override
        public Class<?> getType(ELContext context, Object base, Object property) {
            if (base == target && "value".equals(property)) {
                context.setPropertyResolved(base, property);
                return String.class;
            }
            return null;
        }

        @Override
        public void setValue(ELContext context, Object base, Object property, Object value) {
            if (base == target && "value".equals(property)) {
                context.setPropertyResolved(base, property);
                this.value = value;
            }
        }

        @Override
        public boolean isReadOnly(ELContext context, Object base, Object property) {
            if (base == target && "value".equals(property)) context.setPropertyResolved(base, property);
            return false;
        }

        @Override
        public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return Object.class;
        }
    }

    @Test
    void wrapsResolverFailuresAndPreservesTheirCause() {
        var context = new StandardELContext(factory);
        var failure = new IllegalStateException("resolver failure");
        context.addELResolver(new ELResolver() {
            @Override
            public Object getValue(ELContext context, Object base, Object property) {
                throw failure;
            }

            @Override
            public Class<?> getType(ELContext context, Object base, Object property) {
                context.setPropertyResolved(true);
                return Object.class;
            }

            @Override
            public void setValue(ELContext context, Object base, Object property, Object value) {
                throw failure;
            }

            @Override
            public boolean isReadOnly(ELContext context, Object base, Object property) {
                return false;
            }

            @Override
            public Class<?> getCommonPropertyType(ELContext context, Object base) {
                return Object.class;
            }
        });
        context.getVariableMapper().setVariable("target", factory.createValueExpression(new Object(), Object.class));
        var expression = expression(context, "target.value");
        assertSame(failure, assertThrows(ELException.class, () -> expression.getValue(context)).getCause());
        assertSame(failure, assertThrows(ELException.class, () -> expression.setValue(context, "new")).getCause());
    }
}

