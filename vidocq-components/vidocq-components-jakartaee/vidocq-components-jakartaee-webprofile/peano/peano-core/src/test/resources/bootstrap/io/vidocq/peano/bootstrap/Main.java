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
import jakarta.el.MethodNotFoundException;
import jakarta.el.ELClass;
import jakarta.el.ELException;
import jakarta.el.StandardELContext;
import io.vidocq.peano.bootstrap.hidden.HiddenBean;
import io.vidocq.peano.bootstrap.exported.PublicRecord;

/** Independent consumer: references only the Jakarta API. */
public final class Main {
    public static void main(String[] args) throws ReflectiveOperationException {
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
        var pipeline = factory.createValueExpression(context,
                "${[3,1,2].stream().filter(x->x>1).sorted().map(x->x*2).toList()}", Object.class);
        if (!java.util.List.of(4L, 6L).equals(pipeline.getValue(context))
                || !Long.valueOf(42L).equals(factory.createValueExpression(context,
                        "${[].stream().findFirst().orElseGet(()->42)}", Object.class).getValue(context))) {
            throw new AssertionError("Collection dispatch failed inside the closed provider module");
        }
        System.out.println("PEANO_MODULE_OK");
    }

    private static void verifyResolvers(ExpressionFactory factory) throws ReflectiveOperationException {
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
        var recordMethod = factory.createMethodExpression(context, "#{record.count()}", long.class, null);
        var reference = recordMethod.getMethodReference(context);
        if (!Long.valueOf(42L).equals(recordMethod.invoke(context, null))
                || reference.getMethodInfo().getReturnType() != long.class
                || java.util.Arrays.stream(reference.getAnnotations()).noneMatch(a -> a.annotationType() == Deprecated.class)) {
            throw new AssertionError("API-only export failed for method invocation or metadata");
        }
        context.getFunctionMapper().mapFunction("f", "twice", PublicRecord.class.getMethod("twice", long.class));
        if (!Long.valueOf(84L).equals(factory.createValueExpression(context, "${f:twice(42)}", Object.class).getValue(context))) {
            throw new AssertionError("API-only export failed for a mapped function");
        }
        context.getVariableMapper().setVariable("recordClass", factory.createValueExpression(new ELClass(PublicRecord.class), Object.class));
        for (Class<?> parameter : new Class<?>[] {int.class, Integer.class}) {
            var overload = factory.createMethodExpression(context, "#{recordClass.boxed}", String.class,
                    new Class<?>[] {parameter});
            if (overload.getMethodInfo(context).getParamTypes()[0] != parameter
                    || overload.getMethodReference(context).getMethodInfo().getParamTypes()[0] != parameter
                    || !(parameter == int.class ? "primitive" : "reference").equals(overload.invoke(context, new Object[] {1L}))) {
                throw new AssertionError("Primitive/wrapper overload selection lost the declared descriptor");
            }
        }
        var ambiguous = factory.createMethodExpression(context, "#{recordClass.pick}", String.class,
                new Class<?>[] {PublicRecord.Argument.class});
        try {
            ambiguous.getMethodInfo(context);
            throw new AssertionError("Unrelated matching interfaces must remain ambiguous");
        } catch (MethodNotFoundException expected) {
            // B and C both match; C only dominates A.
        }
        var hiddenMethod = factory.createMethodExpression(context, "#{bean.greet(2)}", null, null);
        if (!"changed2".equals(hiddenMethod.invoke(context, null))) {
            throw new AssertionError("Custom invocation crossed the wrong module boundary");
        }
        try {
            hiddenMethod.getMethodInfo(context);
            throw new AssertionError("Metadata fallback must not expose a closed package");
        } catch (MethodNotFoundException expected) {
            // Generated metadata is a later milestone; the public fallback stays closed.
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
        try {
            factory.createValueExpression(fallback, "${bean.greet(2)}", Object.class).getValue(fallback);
            throw new AssertionError("Fallback invoked across a non-exported package");
        } catch (ELException expected) {
            if (!(expected instanceof MethodNotFoundException) && !(expected.getCause() instanceof NullPointerException)) {
                throw new AssertionError("Unexpected inaccessible-method failure", expected);
            }
            // Only the application resolver may invoke the hidden method.
        }
    }

    private static final class HiddenResolver extends ELResolver {
        @Override
        public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
            if (base instanceof HiddenBean bean && "greet".equals(method)) {
                context.setPropertyResolved(base, method);
                return bean.greet(context.convertToType(arguments[0], long.class));
            }
            return null;
        }

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

