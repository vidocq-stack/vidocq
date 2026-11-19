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
package io.vidocq.bodoni.peano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import jakarta.el.BeanELResolver;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.jstl.sql.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JSTL {@code Result} of {@code sql:query} (Tags 3.0 section 10.3) under reflection denial:
 * implementations are internal classes, so only the table of this module, reached by supertype
 * lookup on {@link Result}, can serve its properties.
 */
class ResultAccessTest {
    private static final String POLICY = "peano.reflection";
    private static final String[] PROPERTIES = {"rows", "rowsByIndex", "columnNames", "rowCount", "limitedByMaxRows"};
    private final ExpressionFactory factory = ExpressionFactory.newInstance();
    private final StandardELContext context = new StandardELContext(factory);
    private String previous;

    @BeforeEach
    void deny() {
        previous = System.getProperty(POLICY);
        System.setProperty(POLICY, "deny");
    }

    @AfterEach
    void restore() {
        if (previous == null) {
            System.clearProperty(POLICY);
        } else {
            System.setProperty(POLICY, previous);
        }
    }

    private ValueExpression expression(Object result, String text) {
        context.getVariableMapper().setVariable("r", factory.createValueExpression(result, Object.class));
        return factory.createValueExpression(context, text, Object.class);
    }

    private Object eval(Object result, String text) {
        return expression(result, text).getValue(context);
    }

    @SuppressWarnings("unchecked")
    private static SortedMap<String, Object>[] twoRows() {
        var first = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
        first.put("id", 1);
        first.put("title", "Bodoni");
        var second = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
        second.put("id", 2);
        second.put("title", "Tipografico");
        return new SortedMap[] {first, second};
    }

    /** A non-public result: only the interface is known to the table. */
    private static final class Fixed implements Result {
        @Override
        public SortedMap[] getRows() {
            return twoRows();
        }

        @Override
        public Object[][] getRowsByIndex() {
            return new Object[][] {{1, "Bodoni"}, {2, "Tipografico"}};
        }

        @Override
        public String[] getColumnNames() {
            return new String[] {"id", "title"};
        }

        @Override
        public int getRowCount() {
            return 2;
        }

        @Override
        public boolean isLimitedByMaxRows() {
            return false;
        }
    }

    /** A public result, which the reflective oracle can read. */
    public static final class PublicFixed implements Result {
        @Override
        public SortedMap[] getRows() {
            return twoRows();
        }

        @Override
        public Object[][] getRowsByIndex() {
            return new Object[][] {{1, "Bodoni"}};
        }

        @Override
        public String[] getColumnNames() {
            return new String[] {"id", "title"};
        }

        @Override
        public int getRowCount() {
            return 2;
        }

        @Override
        public boolean isLimitedByMaxRows() {
            return false;
        }
    }

    @Test
    void readsEveryPropertyOfAResult() {
        var result = new Fixed();
        assertEquals(2, eval(result, "${r.rowCount}"));
        assertEquals("Tipografico", eval(result, "${r.rows[1].title}"));
        assertEquals("Tipografico", eval(result, "${r.rows[1].TITLE}"));
        assertEquals("id", eval(result, "${r.columnNames[0]}"));
        assertEquals("Bodoni", eval(result, "${r.rowsByIndex[0][1]}"));
        assertEquals(false, eval(result, "${r.limitedByMaxRows}"));
        assertEquals(2, eval(result, "${r['rowCount']}"));
    }

    @Test
    void invokesTheResultAccessorMethods() {
        var result = new Fixed();
        assertEquals(2, eval(result, "${r.getRowCount()}"));
        assertEquals("id", eval(result, "${r.getColumnNames()[0]}"));
        assertFalse((Boolean) eval(result, "${r.isLimitedByMaxRows()}"));
    }

    @Test
    void everyPropertyIsReadOnly() {
        var result = new Fixed();
        for (String property : PROPERTIES) {
            var value = expression(result, "${r." + property + "}");
            assertTrue(value.isReadOnly(context), property);
            assertThrows(PropertyNotWritableException.class, () -> value.setValue(context, 1), property);
        }
    }

    @Test
    void unknownPropertiesStillHitTheDenyGuard() {
        var result = new Fixed();
        var failure = assertThrows(ELException.class, () -> eval(result, "${r.nope}"));
        assertEquals("Reflection fallback denied for " + Fixed.class.getName()
                + "; register a generated accessor provider or a custom resolver", failure.getMessage());
    }

    @Test
    void theProviderIsDeclaredByTheJavaModule() {
        var module = ModuleLayer.boot().findModule("io.vidocq.bodoni.peano").orElseThrow();
        var provides = module.getDescriptor().provides().stream()
                .filter(p -> p.service().equals("io.vidocq.peano.spi.AccessorProvider"))
                .findFirst().orElseThrow();
        assertEquals(List.of("io.vidocq.bodoni.peano.internal.BodoniAccessorProvider"), provides.providers());
    }

    @Test
    void agreesWithBeanELResolverOnEveryProperty() {
        var result = new PublicFixed();
        var oracle = new BeanELResolver();
        var scoped = new StandardELContext(factory);
        scoped.getVariableMapper().setVariable("r", factory.createValueExpression(result, Object.class));
        for (String property : PROPERTIES) {
            // The oracle needs reflection, which the deny policy refuses only for the table-less path.
            System.clearProperty(POLICY);
            var expected = oracle.getValue(scoped, result, property);
            System.setProperty(POLICY, "deny");
            var value = expression(result, "${r." + property + "}");
            var actual = value.getValue(context);
            if (expected instanceof Object[] array) {
                assertEquals(array.length, ((Object[]) actual).length, property);
            } else {
                assertEquals(expected, actual, property);
            }
            assertEquals(oracle.isReadOnly(scoped, result, property), value.isReadOnly(context), property);
        }
    }
}
