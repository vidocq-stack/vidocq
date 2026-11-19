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

import java.util.SortedMap;
import java.util.TreeMap;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import jakarta.servlet.jsp.jstl.sql.Result;
import org.junit.jupiter.api.Test;

class ResultAccessorTableTest {
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] PROPERTIES = {"rows", "rowsByIndex", "columnNames", "rowCount", "limitedByMaxRows"};
    private static final String[] METHODS = {"getRows", "getRowsByIndex", "getColumnNames", "getRowCount",
        "isLimitedByMaxRows"};
    private static final String NOT_A_RESULT = "not a result";
    private static final String ROW_COUNT = "rowCount";
    private static final String GET_ROW_COUNT = "getRowCount";

    private final ResultAccessorTable table = new ResultAccessorTable();
    private final ELContext context = new StandardELContext(ExpressionFactory.newInstance());

    /** A result whose getters fail with the given exception when one is supplied. */
    private static final class Rows implements Result {
        private final SortedMap<String, Object>[] rows;
        private final Object[][] rowsByIndex = {{"a", 1}};
        private final String[] columnNames = {"c1", "c2"};
        private final RuntimeException failure;

        @SuppressWarnings("unchecked")
        Rows(RuntimeException failure) {
            this.failure = failure;
            var row = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
            row.put("c1", "a");
            this.rows = new SortedMap[] {row};
        }

        private <T> T value(T value) {
            if (failure != null) {
                throw failure;
            }
            return value;
        }

        @Override
        public SortedMap[] getRows() {
            return value(rows);
        }

        @Override
        public Object[][] getRowsByIndex() {
            return value(rowsByIndex);
        }

        @Override
        public String[] getColumnNames() {
            return value(columnNames);
        }

        @Override
        public int getRowCount() {
            return value(1);
        }

        @Override
        public boolean isLimitedByMaxRows() {
            return value(true);
        }
    }

    private static Object[] values(Rows result) {
        return new Object[] {result.getRows(), result.getRowsByIndex(), result.getColumnNames(), 1, true};
    }

    @Test
    void targetsTheInterface() {
        assertEquals(Result.class, table.targetType());
    }

    @Test
    void getValueReturnsEveryPropertyAndMarksItResolved() {
        var result = new Rows(null);
        var expected = values(result);
        for (int i = 0; i < PROPERTIES.length; i++) {
            context.setPropertyResolved(false);
            assertEquals(expected[i], table.getValue(context, result, PROPERTIES[i]), PROPERTIES[i]);
            assertTrue(context.isPropertyResolved(), PROPERTIES[i]);
        }
    }

    @Test
    void foreignBasesAndPropertiesAreNotResolved() {
        var result = new Rows(null);
        context.setPropertyResolved(false);
        assertNull(table.getValue(context, result, "other"));
        assertNull(table.getValue(context, result, 1));
        assertNull(table.getValue(context, NOT_A_RESULT, ROW_COUNT));
        assertNull(table.getType(context, NOT_A_RESULT, ROW_COUNT));
        assertFalse(table.isReadOnly(context, result, "other"));
        table.setValue(context, NOT_A_RESULT, ROW_COUNT, 1);
        table.setValue(context, result, "other", 1);
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void typeAndReadOnlyOnAResultProperty() {
        var result = new Rows(null);
        context.setPropertyResolved(false);
        assertNull(table.getType(context, result, ROW_COUNT));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertTrue(table.isReadOnly(context, result, "limitedByMaxRows"));
        assertTrue(context.isPropertyResolved());
    }

    @Test
    void everyPropertyRejectsWrites() {
        var result = new Rows(null);
        for (String property : PROPERTIES) {
            var failure = assertThrows(PropertyNotWritableException.class,
                    () -> table.setValue(context, result, property, 1), property);
            assertEquals("Read-only property " + property, failure.getMessage());
        }
    }

    @Test
    void commonPropertyTypeIsStringOnlyForAResult() {
        assertEquals(String.class, table.getCommonPropertyType(context, new Rows(null)));
        assertNull(table.getCommonPropertyType(context, NOT_A_RESULT));
        assertNull(table.getCommonPropertyType(context, null));
    }

    @Test
    void contextIsRequired() {
        var result = new Rows(null);
        assertThrows(NullPointerException.class, () -> table.getValue(null, result, ROW_COUNT));
        assertThrows(NullPointerException.class, () -> table.getCommonPropertyType(null, result));
        assertThrows(NullPointerException.class,
                () -> table.getMethodReference(null, result, GET_ROW_COUNT, NO_TYPES, NO_ARGS));
        assertThrows(NullPointerException.class, () -> table.invoke(null, result, GET_ROW_COUNT, NO_TYPES, NO_ARGS));
    }

    @Test
    void invokesEveryGetter() {
        var result = new Rows(null);
        var expected = values(result);
        for (int i = 0; i < METHODS.length; i++) {
            context.setPropertyResolved(false);
            assertEquals(expected[i], table.invoke(context, result, METHODS[i], NO_TYPES, NO_ARGS), METHODS[i]);
            assertTrue(context.isPropertyResolved(), METHODS[i]);
        }
        assertEquals(1, table.invoke(context, result, GET_ROW_COUNT, null, null));
    }

    @Test
    void doesNotClaimInvocationsOfAnotherShape() {
        var result = new Rows(null);
        context.setPropertyResolved(false);
        assertNull(table.invoke(context, result, GET_ROW_COUNT, new Class<?>[] {int.class}, new Object[] {1}));
        assertNull(table.invoke(context, result, "toString", NO_TYPES, NO_ARGS));
        assertNull(table.invoke(context, result, 7, NO_TYPES, NO_ARGS));
        assertNull(table.invoke(context, NOT_A_RESULT, GET_ROW_COUNT, NO_TYPES, NO_ARGS));
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void methodReferenceNamesEveryGetter() {
        var result = new Rows(null);
        for (String method : METHODS) {
            var reference = table.getMethodReference(context, result, method, NO_TYPES, NO_ARGS);
            assertNotNull(reference, method);
            assertSame(result, reference.getBase());
            assertEquals(method, reference.getMethodInfo().getName());
            assertEquals(0, reference.getMethodInfo().getParamTypes().length);
            assertArrayEquals(NO_ARGS, reference.getEvaluatedParameters());
        }
    }

    @Test
    void methodReferenceIgnoresOtherShapes() {
        var result = new Rows(null);
        assertNull(table.getMethodReference(context, result, GET_ROW_COUNT, new Class<?>[] {int.class},
                new Object[] {1}));
        assertNull(table.getMethodReference(context, result, "toString", NO_TYPES, NO_ARGS));
        assertNull(table.getMethodReference(context, NOT_A_RESULT, GET_ROW_COUNT, NO_TYPES, NO_ARGS));
    }

    @Test
    void getterFailuresAreWrapped() {
        var cause = new IllegalStateException("boom");
        var result = new Rows(cause);
        var failure = assertThrows(ELException.class,
                () -> table.invoke(context, result, GET_ROW_COUNT, NO_TYPES, NO_ARGS));
        assertEquals("Result method invocation failed", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    void elExceptionsPassThroughUnwrapped() {
        var original = new ELException("original");
        var result = new Rows(original);
        var failure = assertThrows(ELException.class,
                () -> table.invoke(context, result, GET_ROW_COUNT, NO_TYPES, NO_ARGS));
        assertSame(original, failure);
    }
}
