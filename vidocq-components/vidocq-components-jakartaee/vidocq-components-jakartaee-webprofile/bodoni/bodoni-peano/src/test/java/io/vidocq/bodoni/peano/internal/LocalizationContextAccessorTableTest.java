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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.ResourceBundle;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import org.junit.jupiter.api.Test;

class LocalizationContextAccessorTableTest {
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] PROPERTIES = {"resourceBundle", "locale"};
    private static final String[] METHODS = {"getResourceBundle", "getLocale"};

    private final LocalizationContextAccessorTable table = new LocalizationContextAccessorTable();
    private final ELContext context = new StandardELContext(ExpressionFactory.newInstance());
    private final ResourceBundle bundle = new ListResourceBundle() {
        @Override
        protected Object[][] getContents() {
            return new Object[][] {{"k", "v"}};
        }
    };

    /** A context whose getters fail with the given exception. */
    private static final class Failing extends LocalizationContext {
        private final RuntimeException failure;

        Failing(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Locale getLocale() {
            throw failure;
        }
    }

    @Test
    void targetsTheClass() {
        assertEquals(LocalizationContext.class, table.targetType());
    }

    @Test
    void getValueReturnsEveryPropertyAndMarksItResolved() {
        var ctx = new LocalizationContext(bundle, Locale.ENGLISH);
        var expected = new Object[] {bundle, Locale.ENGLISH};
        for (int i = 0; i < PROPERTIES.length; i++) {
            context.setPropertyResolved(false);
            assertSame(expected[i], table.getValue(context, ctx, PROPERTIES[i]), PROPERTIES[i]);
            assertTrue(context.isPropertyResolved(), PROPERTIES[i]);
        }
    }

    @Test
    void foreignBasesAndPropertiesAreNotResolved() {
        var ctx = new LocalizationContext();
        context.setPropertyResolved(false);
        assertNull(table.getValue(context, ctx, "other"));
        assertNull(table.getValue(context, "text", "locale"));
        assertFalse(table.isReadOnly(context, ctx, "other"));
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void everyPropertyRejectsWrites() {
        var ctx = new LocalizationContext();
        for (String property : PROPERTIES) {
            var failure = assertThrows(PropertyNotWritableException.class,
                    () -> table.setValue(context, ctx, property, 1), property);
            assertEquals("Read-only property " + property, failure.getMessage());
        }
    }

    @Test
    void invokesEveryGetter() {
        var ctx = new LocalizationContext(bundle, Locale.ENGLISH);
        var expected = new Object[] {bundle, Locale.ENGLISH};
        for (int i = 0; i < METHODS.length; i++) {
            context.setPropertyResolved(false);
            assertSame(expected[i], table.invoke(context, ctx, METHODS[i], NO_TYPES, NO_ARGS), METHODS[i]);
            assertTrue(context.isPropertyResolved(), METHODS[i]);
        }
    }

    @Test
    void getterFailuresAreWrapped() {
        var cause = new IllegalStateException("boom");
        var ctx = new Failing(cause);
        var failure = assertThrows(ELException.class,
                () -> table.invoke(context, ctx, "getLocale", NO_TYPES, NO_ARGS));
        assertSame(cause, failure.getCause());
    }
}
