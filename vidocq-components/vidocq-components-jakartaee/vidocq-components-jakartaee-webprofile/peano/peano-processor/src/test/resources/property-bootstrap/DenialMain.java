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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class DenialMain {
    public static void main(String[] args) throws Exception {
        boolean deny="deny".equals(System.getProperty("peano.reflection"));
        var factory=ExpressionFactory.newInstance();
        var context=new StandardELContext(factory);
        context.getVariableMapper().setVariable("bean",factory.createValueExpression(new Bean(),Object.class));
        context.getVariableMapper().setVariable("foreign",factory.createValueExpression(new AtomicInteger(7),Object.class));
        context.getVariableMapper().setVariable("math",factory.createValueExpression(new ELClass(Math.class),Object.class));
        context.getVariableMapper().setVariable("calendar",factory.createValueExpression(new java.util.GregorianCalendar(),Object.class));
        context.getVariableMapper().setVariable("record",factory.createValueExpression(new demo.exported.PublicBean.FallbackRecord(7),Object.class));
        context.getVariableMapper().setVariable("map",factory.createValueExpression(Map.of("key",7),Object.class));
        context.getVariableMapper().setVariable("list",factory.createValueExpression(List.of(3,4),Object.class));
        context.getVariableMapper().setVariable("array",factory.createValueExpression(new int[]{3,4},Object.class));
        context.getVariableMapper().setVariable("item",factory.createValueExpression(new Item(7,"value"),Object.class));
        value(factory,context,"${bean.add(2,3)}",5);
        var property=factory.createValueExpression(context,"${bean.name}",Object.class);
        if (property.getType(context)!=String.class || property.isReadOnly(context))
            throw new AssertionError("Generated writable property metadata failed");
        property.setValue(context,"updated");
        value(factory,context,"${bean.name}","updated");
        value(factory,context,"${item.value}",7);
        value(factory,context,"${map.key}",7);
        value(factory,context,"${list[1]}",4);
        value(factory,context,"${array.length}",2);
        value(factory,context,"${[1,2,3].stream().sum()}",6L);
        var generated=factory.createMethodExpression(context,"#{bean.add(2,3)}",int.class,null);
        if (generated.getMethodInfo(context).getReturnType()!=int.class || !Integer.valueOf(5).equals(generated.invoke(context,null)))
            throw new AssertionError("Generated metadata/invocation failed");
        var callback=(Callbacks.Sum)factory.createValueExpression(context,"${(a,b)->a+b}",Callbacks.Sum.class).getValue(context);
        if (callback.apply(2,3)!=5) throw new AssertionError("Generated callback failed");
        fallback(deny,AtomicInteger.class,()->factory.createValueExpression(context,"${foreign.class}",Object.class).getValue(context));
        fallback(deny,demo.exported.PublicBean.FallbackRecord.class,()->factory.createValueExpression(context,"${record.value}",Object.class).getValue(context));
        var writable=factory.createValueExpression(context,"${calendar.lenient}",Object.class);
        fallback(deny,java.util.GregorianCalendar.class,()->writable.getType(context));
        fallback(deny,java.util.GregorianCalendar.class,()->writable.isReadOnly(context));
        fallback(deny,java.util.GregorianCalendar.class,()->writable.setValue(context,false));
        fallback(deny,AtomicInteger.class,()->factory.createValueExpression(context,"${foreign.get()}",Object.class).getValue(context));
        fallback(deny,AtomicInteger.class,()->factory.createMethodExpression(context,"#{foreign.get}",int.class,new Class<?>[0]).getMethodInfo(context));
        fallback(deny,AtomicInteger.class,()->factory.createMethodExpression(context,"#{foreign.get}",int.class,new Class<?>[0]).getMethodReference(context));
        fallback(deny,AtomicInteger.class,()->factory.createMethodExpression(context,"#{foreign.get}",null,new Class<?>[0]).invoke(context,new Object[0]));
        var isolated=new StandardELContext(factory) {
            private final ELResolver empty=new CompositeELResolver();
            @Override public ELResolver getELResolver() { return empty; }
        };
        isolated.getVariableMapper().setVariable("foreign",factory.createValueExpression(new AtomicInteger(7),Object.class));
        fallback(deny,AtomicInteger.class,()->factory.createMethodExpression(isolated,"#{foreign.get}",null,new Class<?>[0]).invoke(isolated,new Object[0]));
        fallback(deny,Math.class,()->factory.createValueExpression(context,"${math.PI}",Object.class).getValue(context));
        fallback(deny,Math.class,()->factory.createValueExpression(context,"${math.abs(-3)}",Object.class).getValue(context));
        fallback(deny,StringBuilder.class,()->factory.createValueExpression(context,"${StringBuilder()}",Object.class).getValue(context));
        context.getFunctionMapper().mapFunction("test","abs",Math.class.getMethod("abs",int.class));
        fallback(deny,Math.class,()->factory.createValueExpression(context,"${test:abs(-3)}",Object.class).getValue(context));
        var captured=factory.createValueExpression(context,"${test:abs(-3)}",Object.class);
        var bytes=new java.io.ByteArrayOutputStream();
        try (var output=new java.io.ObjectOutputStream(bytes)) { output.writeObject(captured); }
        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            input.readObject();
            if (deny) throw new AssertionError("Serialized function metadata was restored reflectively");
        } catch (java.io.InvalidObjectException failure) {
            if (!deny || !(failure.getCause() instanceof ELException cause)
                    || !cause.getMessage().contains(Math.class.getName())) throw failure;
        }
        context.getFunctionMapper().mapFunction("test","generated",Bean.class.getMethod("getStaticValue"));
        value(factory,context,"${test:generated()}","static");
        context.addELResolver(new ELResolver() {
            public Object getValue(ELContext c,Object b,Object p) {
                if (b instanceof AtomicInteger && "value".equals(p)) { c.setPropertyResolved(true); return 7; }
                return null;
            }
            public Class<?> getType(ELContext c,Object b,Object p) { return null; }
            public void setValue(ELContext c,Object b,Object p,Object v) {}
            public boolean isReadOnly(ELContext c,Object b,Object p) { return false; }
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return null; }
            public Object invoke(ELContext c,Object b,Object m,Class<?>[] t,Object[] a) {
                if (b instanceof AtomicInteger && "custom".equals(m)) { c.setPropertyResolved(true); return 9; }
                return null;
            }
        });
        value(factory,context,"${foreign.value}",7);
        value(factory,context,"${foreign.custom()}",9);
        System.out.println("PEANO_DENIAL_OK");
    }

    private static void value(ExpressionFactory f,ELContext c,String expression,Object expected) {
        Object actual=f.createValueExpression(c,expression,Object.class).getValue(c);
        if (!expected.equals(actual)) throw new AssertionError(expression+": "+actual);
    }

    private static void fallback(boolean deny,Class<?> type,Runnable operation) {
        try {
            operation.run();
            if (deny) throw new AssertionError("Reflection was allowed for "+type.getName());
        } catch (ELException failure) {
            if (!deny || !failure.getMessage().contains("Reflection fallback denied")
                    || !failure.getMessage().contains(type.getName())) throw failure;
        }
    }
}

