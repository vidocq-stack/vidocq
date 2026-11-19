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
package io.vidocq.peano.accessorconsumer;

import java.util.Collection;
import java.util.List;
import jakarta.el.*;
import io.vidocq.peano.spi.AccessorProvider;
import io.vidocq.peano.spi.AccessorTable;
import io.vidocq.peano.accessorconsumer.exported.PublicItem;

/** The fixture supplies direct tables in a package with no exports or opens. */
public final class Main {
    public static void main(String[] args) {
        var factory = ExpressionFactory.newInstance();
        Module app = Main.class.getModule();
        Module api = ExpressionFactory.class.getModule();
        Module core = factory.getClass().getModule();
        if (!app.getDescriptor().opens().isEmpty()
                || app.isExported(Main.class.getPackageName(), api) || app.isExported(Main.class.getPackageName(), core)) {
            throw new AssertionError("Fixture package must remain closed");
        }
        var context = new StandardELContext(factory);
        var bean = new Bean();
        context.getVariableMapper().setVariable("bean", factory.createValueExpression(bean, Object.class));
        var name = factory.createValueExpression(context, "${bean.name}", Object.class);
        if (name.getType(context) != String.class || name.isReadOnly(context)
                || bean.reads != 0 || name.getValueReference(context).getBase() != bean) {
            throw new AssertionError("Metadata must not invoke the terminal getter");
        }
        if (!"Peano".equals(name.getValue(context)) || bean.reads != 1) {
            throw new AssertionError("Closed bean getter was not dispatched directly");
        }
        name.setValue(context, "changed");
        if (!"changed".equals(bean.name)) throw new AssertionError("Closed bean setter failed");
        var count = factory.createValueExpression(context, "${bean.count}", Object.class);
        count.setValue(context, "42");
        if (bean.count != 42 || count.getType(context) != int.class) {
            throw new AssertionError("Typed setter conversion failed");
        }
        var nullable = factory.createValueExpression(context, "${bean.nullable}", Object.class);
        if (nullable.getValue(context) != null) throw new AssertionError("Resolved null fell through");
        if (!"changed!".equals(factory.createValueExpression(context, "${bean.greet('!')}", Object.class).getValue(context))) {
            throw new AssertionError("Closed bean invocation failed");
        }
        context.getVariableMapper().setVariable("record", factory.createValueExpression(new Item(7), Object.class));
        var item = factory.createValueExpression(context, "${record.value}", Object.class);
        if (!Integer.valueOf(7).equals(item.getValue(context)) || !item.isReadOnly(context) || item.getType(context) != null) {
            throw new AssertionError("Closed record accessor failed");
        }
        try {
            item.setValue(context, 1);
            throw new AssertionError("Record must remain read-only");
        } catch (PropertyNotWritableException expected) {}
        var overridden = new StandardELContext(factory);
        overridden.addELResolver(new CompositeELResolver() {
            @Override public Object getValue(ELContext current, Object base, Object property) {
                if (base == bean && "name".equals(property)) {
                    current.setPropertyResolved(base, property);
                    return "custom";
                }
                return null;
            }
        });
        overridden.getVariableMapper().setVariable("bean", factory.createValueExpression(bean, Object.class));
        if (!"custom".equals(factory.createValueExpression(overridden, "${bean.name}", Object.class).getValue(overridden))) {
            throw new AssertionError("Application resolver must retain precedence");
        }
        try {
            factory.createValueExpression(context, "${bean.unknown}", Object.class).getValue(context);
            throw new AssertionError("Unknown property must remain unresolved");
        } catch (PropertyNotFoundException expected) {}
        String comparisonPackage = PublicItem.class.getPackageName();
        if (!app.isExported(comparisonPackage, api) || app.isExported(comparisonPackage, core)) {
            throw new AssertionError("Comparison record must be exported only to the Jakarta API");
        }
        var recordValue = new PublicItem(42);
        var reflective = new StandardELContext(factory);
        reflective.addELResolver(new RecordELResolver());
        for (var current : List.of(context, reflective)) {
            current.getVariableMapper().setVariable("comparison", factory.createValueExpression(recordValue, Object.class));
        }
        var direct = factory.createValueExpression(context, "${comparison.value}", Object.class);
        var fallback = factory.createValueExpression(reflective, "${comparison.value}", Object.class);
        if (!direct.getValue(context).equals(fallback.getValue(reflective))
                || direct.getType(context) != fallback.getType(reflective)
                || direct.isReadOnly(context) != fallback.isReadOnly(reflective)) {
            throw new AssertionError("Direct table differs from the API record resolver");
        }
        System.out.println("PEANO_ACCESSORS_OK");
    }

    public static final class Bean {
        private String name = "Peano";
        private int count;
        private int reads;
        public String getName() { reads++; return name; }
        public void setName(String value) { name = value; }
        public int getCount() { return count; }
        public void setCount(int value) { count = value; }
        public String getNullable() { return null; }
        public String greet(String suffix) { return name + suffix; }
    }

    public record Item(int value) {}

    public static final class Tables implements AccessorProvider {
        public Tables() {}
        @Override public Collection<? extends AccessorTable> accessors() {
            return List.of(new BeanTable(), new ItemTable(), new PublicItemTable());
        }
    }

    private static final class BeanTable extends AccessorTable {
        @Override public Class<?> targetType() { return Bean.class; }
        private boolean handles(ELContext context, Object base, Object property) {
            if (base instanceof Bean && List.of("name", "count", "nullable").contains(property)) {
                context.setPropertyResolved(base, property);
                return true;
            }
            return false;
        }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            if (!handles(context, base, property)) return null;
            var bean = (Bean) base;
            return switch ((String) property) {
                case "name" -> bean.getName();
                case "count" -> bean.getCount();
                default -> bean.getNullable();
            };
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) {
            if (!handles(context, base, property)) return null;
            return switch ((String) property) {
                case "name" -> String.class;
                case "count" -> int.class;
                default -> null;
            };
        }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) {
            return handles(context, base, property) && "nullable".equals(property);
        }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {
            if (!handles(context, base, property)) return;
            var bean = (Bean) base;
            switch ((String) property) {
                case "name" -> bean.setName(context.convertToType(value, String.class));
                case "count" -> bean.setCount(context.convertToType(value, int.class));
                default -> throw new PropertyNotWritableException("nullable");
            }
        }
        @Override public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] args) {
            if (base instanceof Bean bean && "greet".equals(method)) {
                context.setPropertyResolved(base, method);
                return bean.greet(context.convertToType(args[0], String.class));
            }
            return null;
        }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base instanceof Bean ? String.class : null;
        }
    }

    private static final class ItemTable extends AccessorTable {
        @Override public Class<?> targetType() { return Item.class; }
        private boolean handles(ELContext context, Object base, Object property) {
            if (base instanceof Item && "value".equals(property)) {
                context.setPropertyResolved(base, property);
                return true;
            }
            return false;
        }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            return handles(context, base, property) ? ((Item) base).value() : null;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) {
            handles(context, base, property);
            return null;
        }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) {
            return handles(context, base, property);
        }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {
            if (handles(context, base, property)) throw new PropertyNotWritableException("value");
        }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base instanceof Item ? String.class : null;
        }
    }

    private static final class PublicItemTable extends AccessorTable {
        @Override public Class<?> targetType() { return PublicItem.class; }
        private boolean handles(ELContext context, Object base, Object property) {
            if (base instanceof PublicItem && "value".equals(property)) {
                context.setPropertyResolved(base, property);
                return true;
            }
            return false;
        }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            return handles(context, base, property) ? ((PublicItem) base).value() : null;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) {
            handles(context, base, property);
            return null;
        }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) {
            return handles(context, base, property);
        }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {
            if (handles(context, base, property)) throw new PropertyNotWritableException("value");
        }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base instanceof PublicItem ? String.class : null;
        }
    }
}

