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
import java.util.concurrent.atomic.AtomicInteger;
public class Main {
    public record Value(int number) {}
    public record Missing(int number) {}
    static final AtomicInteger editors=new AtomicInteger();
    public static class EditorV1 extends java.beans.PropertyEditorSupport {
        public EditorV1() { editors.incrementAndGet(); }
        public void setAsText(String text) { setValue(new Value(Integer.parseInt(text))); }
    }
    public static class EditorV2 extends EditorV1 {
        public EditorV2() {}
        public void setAsText(String text) { setValue(new Value(Integer.parseInt(text)+1000)); }
    }
    public static class EmptyEditor extends EditorV1 {
        public EmptyEditor() {}
        public void setAsText(String text) { setValue(new Value(text.isEmpty() ? 99 : Integer.parseInt(text))); }
    }
    public static void main(String[] args) throws Exception {
        boolean installed=Boolean.parseBoolean(args[0]);
        var factory=ExpressionFactory.newInstance();
        java.beans.PropertyEditorManager.registerEditor(Value.class,EditorV1.class);
        if (!installed) {
            try { factory.coerceToType("7",Value.class); throw new AssertionError("Absent module converted text"); }
            catch (ELException expected) {}
            System.out.println("PEANO_COMPAT_ABSENT");
            return;
        }
        if (factory.coerceToType("7",Value.class).number()!=7) throw new AssertionError("Factory conversion failed");
        if (factory.coerceToType("",Value.class)!=null) throw new AssertionError("Failed empty text must be null");
        try { factory.coerceToType("invalid",Value.class); throw new AssertionError("Invalid text accepted"); }
        catch (ELException expected) {
            if (!(expected.getCause() instanceof NumberFormatException)) throw expected;
        }
        java.beans.PropertyEditorManager.registerEditor(Value.class,EmptyEditor.class);
        if (factory.coerceToType("",Value.class).number()!=99) throw new AssertionError("Successful empty conversion bypassed");
        java.beans.PropertyEditorManager.registerEditor(Value.class,EditorV2.class);
        if (factory.coerceToType("7",Value.class).number()!=1007) throw new AssertionError("Registry changes were cached");
        java.beans.PropertyEditorManager.registerEditor(Value.class,EditorV1.class);
        var context=new StandardELContext(factory);
        Value actual=factory.createValueExpression(context,"${'8'}",Value.class).getValue(context);
        if (actual.number()!=8) throw new AssertionError("Context conversion failed");
        context.addELResolver(new ELResolver() {
            public Object getValue(ELContext c,Object b,Object p){ return null; }
            public Class<?> getType(ELContext c,Object b,Object p){ return null; }
            public void setValue(ELContext c,Object b,Object p,Object v){}
            public boolean isReadOnly(ELContext c,Object b,Object p){ return false; }
            public Class<?> getCommonPropertyType(ELContext c,Object b){ return null; }
            public <T> T convertToType(ELContext c,Object value,Class<T> target) {
                if (target!=Value.class) return null;
                c.setPropertyResolved(true);
                return target.cast(new Value(23));
            }
        });
        Value overridden=factory.createValueExpression(context,"${'8'}",Value.class).getValue(context);
        if (overridden.number()!=23) throw new AssertionError("Custom conversion lost precedence");
        Value identity=new Value(2);
        int before=editors.get();
        if (factory.coerceToType(identity,Value.class)!=identity || factory.coerceToType(null,Value.class)!=null
                || editors.get()!=before) throw new AssertionError("Identity/null touched the registry");
        if (factory.coerceToType("",Missing.class)!=null) throw new AssertionError("Missing editor empty text changed");
        try { factory.coerceToType("7",Missing.class); throw new AssertionError("Missing editor converted text"); }
        catch (ELException expected) {}
        try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // The JDK legacy registry is scoped to thread groups, including the virtual-thread group.
            executor.submit(()->java.beans.PropertyEditorManager.registerEditor(Value.class,EditorV1.class)).get();
            before=editors.get();
            var results=new java.util.ArrayList<java.util.concurrent.Future<Value>>();
            for (int i=0;i<100;i++) {
                final int number=i;
                results.add(executor.submit(()->factory.coerceToType(Integer.toString(number),Value.class)));
            }
            for (int i=0;i<results.size();i++)
                if (results.get(i).get().number()!=i) throw new AssertionError("Concurrent editor state was shared");
            if (editors.get()-before!=100) throw new AssertionError("An editor instance was reused");
        }
        System.out.println("PEANO_COMPAT_OK");
    }
}

