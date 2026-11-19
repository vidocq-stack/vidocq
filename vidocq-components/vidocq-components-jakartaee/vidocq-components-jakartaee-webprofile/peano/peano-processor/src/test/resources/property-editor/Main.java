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
package demo;

import jakarta.el.*;

/** An application's explicit legacy editor integration; not shipped with Peano. */
public final class Main {
    public record Value(int amount) {}

    public static final class ValueEditor extends java.beans.PropertyEditorSupport {
        @Override public void setAsText(String text) {
            setValue(new Value(Integer.parseInt(text)));
        }
    }

    private static final class Converter extends ELResolver {
        @Override public <T> T convertToType(ELContext context,Object value,Class<T> target) {
            if (target!=Value.class || !(value instanceof String text)) return null;
            context.setPropertyResolved(true);
            var editor=new ValueEditor();
            try {
                editor.setAsText(text);
                return target.cast(editor.getValue());
            } catch (RuntimeException failure) {
                if (text.isEmpty()) return null;
                throw new ELException("Cannot convert text to Value",failure);
            }
        }
        public Object getValue(ELContext context,Object base,Object property) { return null; }
        public Class<?> getType(ELContext context,Object base,Object property) { return null; }
        public void setValue(ELContext context,Object base,Object property,Object value) {}
        public boolean isReadOnly(ELContext context,Object base,Object property) { return false; }
        public Class<?> getCommonPropertyType(ELContext context,Object base) { return null; }
    }

    public static void main(String[] args) {
        if (ModuleLayer.boot().findModule("java.desktop").isEmpty())
            throw new AssertionError("The application did not resolve its explicit desktop dependency");
        var factory=ExpressionFactory.newInstance();
        java.beans.PropertyEditorManager.registerEditor(Value.class,ValueEditor.class);
        try {
            factory.coerceToType("7",Value.class);
            throw new AssertionError("Peano unexpectedly discovered the global editor registry");
        } catch (ELException expected) {}
        if (factory.coerceToType("",Value.class)!=null)
            throw new AssertionError("Missing-editor empty text must be null");
        var context=new StandardELContext(factory);
        context.addELResolver(new Converter());
        Value value=factory.createValueExpression(context,"${'7'}",Value.class).getValue(context);
        if (value.amount()!=7) throw new AssertionError("Explicit editor integration failed");
        if (factory.createValueExpression(context,"${''}",Value.class).getValue(context)!=null)
            throw new AssertionError("Empty text from a failing editor must be null");
        try {
            factory.createValueExpression(context,"${'invalid'}",Value.class).getValue(context);
            throw new AssertionError("Invalid nonempty text was accepted");
        } catch (ELException expected) {
            if (!(expected.getCause() instanceof NumberFormatException)) throw expected;
        }
        System.out.println("PEANO_EDITOR_OK");
    }
}

