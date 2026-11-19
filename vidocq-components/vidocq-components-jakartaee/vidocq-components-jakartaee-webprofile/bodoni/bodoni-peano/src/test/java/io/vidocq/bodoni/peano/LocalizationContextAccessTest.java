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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.ResourceBundle;
import jakarta.el.BeanELResolver;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JSTL {@code LocalizationContext} (Tags 3.0 section 8.1.1) under reflection denial: a
 * subclass is reached through the table of this module, keyed on the class.
 */
class LocalizationContextAccessTest {
    private static final String POLICY = "peano.reflection";
    private static final String[] PROPERTIES = {"resourceBundle", "locale"};
    private final ExpressionFactory factory = ExpressionFactory.newInstance();
    private final StandardELContext context = new StandardELContext(factory);
    private final ResourceBundle bundle = new ListResourceBundle() {
        @Override
        protected Object[][] getContents() {
            return new Object[][] {{"k", "v"}};
        }
    };
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

    private ValueExpression expression(Object ctx, String text) {
        context.getVariableMapper().setVariable("ctx", factory.createValueExpression(ctx, Object.class));
        return factory.createValueExpression(context, text, Object.class);
    }

    private Object eval(Object ctx, String text) {
        return expression(ctx, text).getValue(context);
    }

    /** A non-public subclass: only the supertype is known to the table. */
    private static final class Sub extends LocalizationContext {
        Sub(ResourceBundle bundle, Locale locale) {
            super(bundle, locale);
        }
    }

    @Test
    void readsBothPropertiesOfALocalizationContext() {
        var ctx = new LocalizationContext(bundle, Locale.ENGLISH);
        assertSame(bundle, eval(ctx, "${ctx.resourceBundle}"));
        assertEquals(Locale.ENGLISH, eval(ctx, "${ctx.locale}"));
    }

    @Test
    void aContextWithoutBundleReadsNull() {
        var ctx = new LocalizationContext();
        assertEquals(true, eval(ctx, "${ctx.resourceBundle == null}"));
        assertEquals(true, eval(ctx, "${ctx.locale == null}"));
    }

    @Test
    void aSubclassIsResolvedThroughTheSupertype() {
        var ctx = new Sub(bundle, Locale.FRENCH);
        assertSame(bundle, eval(ctx, "${ctx.resourceBundle}"));
        assertEquals(Locale.FRENCH, eval(ctx, "${ctx.locale}"));
    }

    @Test
    void invokesTheGetters() {
        var ctx = new LocalizationContext(bundle, Locale.ENGLISH);
        assertEquals(Locale.ENGLISH, eval(ctx, "${ctx.getLocale()}"));
        assertSame(bundle, eval(ctx, "${ctx.getResourceBundle()}"));
    }

    @Test
    void everyPropertyIsReadOnly() {
        var ctx = new LocalizationContext(bundle, Locale.ENGLISH);
        for (String property : PROPERTIES) {
            var value = expression(ctx, "${ctx." + property + "}");
            assertTrue(value.isReadOnly(context), property);
            assertThrows(PropertyNotWritableException.class, () -> value.setValue(context, 1), property);
        }
    }

    @Test
    void unknownPropertiesStillHitTheDenyGuard() {
        var ctx = new Sub(bundle, Locale.ENGLISH);
        var failure = assertThrows(ELException.class, () -> eval(ctx, "${ctx.nope}"));
        assertEquals("Reflection fallback denied for " + Sub.class.getName()
                + "; register a generated accessor provider or a custom resolver", failure.getMessage());
    }

    @Test
    void agreesWithBeanELResolverOnEveryProperty() {
        var ctx = new LocalizationContext(bundle, Locale.ENGLISH);
        var oracle = new BeanELResolver();
        var scoped = new StandardELContext(factory);
        for (String property : PROPERTIES) {
            System.clearProperty(POLICY);
            var expected = oracle.getValue(scoped, ctx, property);
            System.setProperty(POLICY, "deny");
            var value = expression(ctx, "${ctx." + property + "}");
            assertEquals(expected, value.getValue(context), property);
            assertEquals(oracle.isReadOnly(scoped, ctx, property), value.isReadOnly(context), property);
        }
    }
}
