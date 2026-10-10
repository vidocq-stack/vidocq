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
package io.vidocq.peano.bootstrap;

import jakarta.el.ExpressionFactory;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.PropertyNotFoundException;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import io.vidocq.peano.bootstrap.hidden.HiddenBean;
import io.vidocq.peano.bootstrap.exported.PublicRecord;

/** Independent consumer: references only the Jakarta API. */
public final class Main {
    public static void main(String[] args) {
        var factory = ExpressionFactory.newInstance();
        var module = factory.getClass().getModule();
        if (!"io.vidocq.peano.core".equals(module.getName())
                || module.getDescriptor().isAutomatic()
                || !module.getDescriptor().exports().isEmpty()
                || !module.getDescriptor().opens().isEmpty()) {
            throw new AssertionError("Provider module is not encapsulated");
        }
        var context = new StandardELContext(factory);
        var expression = factory.createValueExpression(context, "${1 + 2 * 3}", Object.class);
        if (!Long.valueOf(7L).equals(expression.getValue(context))) {
            throw new AssertionError("Unexpected evaluation result");
        }
        verifyResolvers(factory);
        System.out.println("PEANO_MODULE_OK");
    }

    private static void verifyResolvers(ExpressionFactory factory) {
        Module app = Main.class.getModule();
        Module api = ExpressionFactory.class.getModule();
        Module core = factory.getClass().getModule();
        String hiddenPackage = "io.vidocq.peano.bootstrap.hidden";
        String recordPackage = "io.vidocq.peano.bootstrap.exported";
        if (app.isExported(hiddenPackage, api) || app.isOpen(hiddenPackage, api)
                || !app.isExported(recordPackage, api) || app.isExported(recordPackage, core)
                || app.isExported(recordPackage) || !app.getDescriptor().opens().isEmpty()) {
            throw new AssertionError("Consumer module boundaries changed");
        }
        var bean = new HiddenBean();
        var context = new StandardELContext(factory);
        context.addELResolver(new HiddenResolver());
        context.getVariableMapper().setVariable("bean", factory.createValueExpression(bean, Object.class));
        var expression = factory.createValueExpression(context, "${bean.name}", Object.class);
        if (!"hidden".equals(expression.getValue(context)) || expression.getType(context) != String.class
                || expression.isReadOnly(context) || expression.getValueReference(context).getBase() != bean) {
            throw new AssertionError("Custom resolver did not resolve the hidden bean");
        }
        expression.setValue(context, "changed");
        if (!"changed".equals(bean.getName())) throw new AssertionError("Hidden bean write failed");

        context.getVariableMapper().setVariable("record", factory.createValueExpression(new PublicRecord(42), Object.class));
        var record = factory.createValueExpression(context, "${record.count}", Object.class);
        if (!Long.valueOf(42L).equals(record.getValue(context)) || !record.isReadOnly(context)) {
            throw new AssertionError("Qualified record export was not respected");
        }

        var fallback = new StandardELContext(factory);
        fallback.getVariableMapper().setVariable("bean", factory.createValueExpression(bean, Object.class));
        var blocked = factory.createValueExpression(fallback, "${bean.name}", Object.class);
        try {
            blocked.getValue(fallback);
            throw new AssertionError("Fallback crossed a non-exported package");
        } catch (PropertyNotFoundException expected) {
            // The API resolver cannot reach the accessor.
        }
        try {
            blocked.setValue(fallback, "forbidden");
            throw new AssertionError("Fallback wrote across a non-exported package");
        } catch (PropertyNotWritableException expected) {
            // The API resolver cannot reach the accessor.
        }
        if (!"changed".equals(bean.getName())) throw new AssertionError("Rejected fallback write mutated the bean");
    }

    private static final class HiddenResolver extends ELResolver {
        private boolean handles(ELContext context, Object base, Object property) {
            if (base instanceof HiddenBean && "name".equals(property)) {
                context.setPropertyResolved(base, property);
                return true;
            }
            return false;
        }

        @Override
        public Object getValue(ELContext context, Object base, Object property) {
            return handles(context, base, property) ? ((HiddenBean) base).getName() : null;
        }

        @Override
        public Class<?> getType(ELContext context, Object base, Object property) {
            return handles(context, base, property) ? String.class : null;
        }

        @Override
        public void setValue(ELContext context, Object base, Object property, Object value) {
            if (handles(context, base, property)) ((HiddenBean) base).setName((String) value);
        }

        @Override
        public boolean isReadOnly(ELContext context, Object base, Object property) {
            handles(context, base, property);
            return false;
        }

        @Override
        public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base instanceof HiddenBean ? String.class : null;
        }
    }
}

