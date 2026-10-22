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
package io.vidocq.bodoni.peano.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import jakarta.servlet.jsp.jstl.core.LoopTagStatus;
import org.junit.jupiter.api.Test;

/** Direct calls on the {@code LoopTagStatus} accessor table, outside any expression. */
class LoopTagStatusAccessorTableTest {
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] PROPERTIES = {"current", "index", "count", "first", "last", "begin", "end", "step"};
    private static final String[] METHODS = {"getCurrent", "getIndex", "getCount", "isFirst", "isLast",
        "getBegin", "getEnd", "getStep"};
    private static final Object[] VALUES = {"c", 4, 5, true, false, 1, 9, 3};

    private final LoopTagStatusAccessorTable table = new LoopTagStatusAccessorTable();
    private final ELContext context = new StandardELContext(ExpressionFactory.newInstance());

    /** A status whose getters fail with the given exception when one is supplied. */
    private static final class Status implements LoopTagStatus {
        private final RuntimeException failure;

        Status(RuntimeException failure) {
            this.failure = failure;
        }

        private <T> T value(T value) {
            if (failure != null) {
                throw failure;
            }
            return value;
        }

        @Override
        public Object getCurrent() {
            return value("c");
        }

        @Override
        public int getIndex() {
            return value(4);
        }

        @Override
        public int getCount() {
            return value(5);
        }

        @Override
        public boolean isFirst() {
            return value(true);
        }

        @Override
        public boolean isLast() {
            return value(false);
        }

        @Override
        public Integer getBegin() {
            return value(1);
        }

        @Override
        public Integer getEnd() {
            return value(9);
        }

        @Override
        public Integer getStep() {
            return value(3);
        }
    }

    @Test
    void targetsTheInterface() {
        assertEquals(LoopTagStatus.class, table.targetType());
    }

    @Test
    void getValueReturnsEveryPropertyAndMarksItResolved() {
        var status = new Status(null);
        for (int i = 0; i < PROPERTIES.length; i++) {
            context.setPropertyResolved(false);
            assertEquals(VALUES[i], table.getValue(context, status, PROPERTIES[i]), PROPERTIES[i]);
            assertTrue(context.isPropertyResolved(), PROPERTIES[i]);
        }
    }

    @Test
    void foreignBasesAndPropertiesAreNotResolved() {
        var status = new Status(null);
        context.setPropertyResolved(false);
        assertNull(table.getValue(context, status, "other"));
        assertNull(table.getValue(context, status, 1));
        assertNull(table.getValue(context, "not a status", "index"));
        assertNull(table.getType(context, "not a status", "index"));
        assertFalse(table.isReadOnly(context, status, "other"));
        table.setValue(context, "not a status", "index", 1);
        table.setValue(context, status, "other", 1);
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void typeAndReadOnlyOnAStatusProperty() {
        var status = new Status(null);
        context.setPropertyResolved(false);
        assertNull(table.getType(context, status, "index"));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertTrue(table.isReadOnly(context, status, "first"));
        assertTrue(context.isPropertyResolved());
    }

    @Test
    void everyPropertyRejectsWrites() {
        var status = new Status(null);
        for (String property : PROPERTIES) {
            var failure = assertThrows(PropertyNotWritableException.class,
                    () -> table.setValue(context, status, property, 1), property);
            assertEquals("Read-only property " + property, failure.getMessage());
        }
    }

    @Test
    void commonPropertyTypeIsStringOnlyForAStatus() {
        assertEquals(String.class, table.getCommonPropertyType(context, new Status(null)));
        assertNull(table.getCommonPropertyType(context, "not a status"));
        assertNull(table.getCommonPropertyType(context, null));
    }

    @Test
    void contextIsRequired() {
        var status = new Status(null);
        assertThrows(NullPointerException.class, () -> table.getValue(null, status, "index"));
        assertThrows(NullPointerException.class, () -> table.getCommonPropertyType(null, status));
        assertThrows(NullPointerException.class,
                () -> table.getMethodReference(null, status, "getIndex", NO_TYPES, NO_ARGS));
        assertThrows(NullPointerException.class, () -> table.invoke(null, status, "getIndex", NO_TYPES, NO_ARGS));
    }

    @Test
    void invokesEveryGetter() {
        var status = new Status(null);
        for (int i = 0; i < METHODS.length; i++) {
            context.setPropertyResolved(false);
            assertEquals(VALUES[i], table.invoke(context, status, METHODS[i], NO_TYPES, NO_ARGS), METHODS[i]);
            assertTrue(context.isPropertyResolved(), METHODS[i]);
        }
        assertEquals(4, table.invoke(context, status, "getIndex", null, null));
    }

    @Test
    void doesNotClaimInvocationsOfAnotherShape() {
        var status = new Status(null);
        context.setPropertyResolved(false);
        assertNull(table.invoke(context, status, "getIndex", new Class<?>[] {int.class}, new Object[] {1}));
        assertNull(table.invoke(context, status, "toString", NO_TYPES, NO_ARGS));
        assertNull(table.invoke(context, status, 7, NO_TYPES, NO_ARGS));
        assertNull(table.invoke(context, "not a status", "getIndex", NO_TYPES, NO_ARGS));
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void methodReferenceNamesEveryGetter() {
        var status = new Status(null);
        for (String method : METHODS) {
            var reference = table.getMethodReference(context, status, method, NO_TYPES, NO_ARGS);
            assertNotNull(reference, method);
            assertSame(status, reference.getBase());
            assertEquals(method, reference.getMethodInfo().getName());
            assertEquals(0, reference.getMethodInfo().getParamTypes().length);
            assertArrayEquals(NO_ARGS, reference.getEvaluatedParameters());
        }
        var withNulls = table.getMethodReference(context, status, "getIndex", null, null);
        assertEquals(0, withNulls.getEvaluatedParameters().length);
    }

    @Test
    void methodReferenceIgnoresOtherShapes() {
        var status = new Status(null);
        assertNull(table.getMethodReference(context, status, "getIndex", new Class<?>[] {int.class},
                new Object[] {1}));
        assertNull(table.getMethodReference(context, status, "toString", NO_TYPES, NO_ARGS));
        assertNull(table.getMethodReference(context, "not a status", "getIndex", NO_TYPES, NO_ARGS));
    }

    @Test
    void getterFailuresAreWrapped() {
        var cause = new IllegalStateException("boom");
        var failure = assertThrows(ELException.class,
                () -> table.invoke(context, new Status(cause), "getIndex", NO_TYPES, NO_ARGS));
        assertEquals("Loop status method invocation failed", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    void elExceptionsPassThroughUnwrapped() {
        var original = new ELException("original");
        var failure = assertThrows(ELException.class,
                () -> table.invoke(context, new Status(original), "getCount", NO_TYPES, NO_ARGS));
        assertSame(original, failure);
    }
}
