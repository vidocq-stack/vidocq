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

class ResolverConversionTest {
    record NumericToken(long value) {
    }

    @Test
    void resolverConversionsApplyToOperandsConditionalsAndExpectedTypes() {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        context.addELResolver(new ConversionResolver());
        context.getVariableMapper().setVariable("token", factory.createValueExpression(new NumericToken(7), Object.class));
        assertEquals(8L, factory.createValueExpression(context, "${token + 1}", Object.class).<Object>getValue(context));
        assertEquals(true, factory.createValueExpression(context, "${token > 5}", Object.class).<Object>getValue(context));
        assertEquals(true, factory.createValueExpression(context, "${token == 7}", Object.class).<Object>getValue(context));
        assertEquals(-42L, factory.createValueExpression(context, "${-'magic'}", Object.class).<Object>getValue(context));
        assertEquals(1L, factory.createValueExpression(context, "${'truthy' ? 1 : 2}", Object.class).<Object>getValue(context));
        assertEquals(true, factory.createValueExpression(context, "${'truthy' and true}", Object.class).<Object>getValue(context));
        assertEquals(7L, factory.createValueExpression(context, "${token}", Long.class).<Object>getValue(context));
    }

    static final class ConversionResolver extends ELResolver {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T convertToType(ELContext context, Object value, Class<T> type) {
            if (value instanceof NumericToken token && (type == Long.class || type == long.class)) {
                context.setPropertyResolved(true);
                return (T) Long.valueOf(token.value());
            }
            if ("magic".equals(value) && (type == Long.class || type == long.class)) {
                context.setPropertyResolved(true);
                return (T) Long.valueOf(42);
            }
            if ("truthy".equals(value) && (type == Boolean.class || type == boolean.class)) {
                context.setPropertyResolved(true);
                return (T) Boolean.TRUE;
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

