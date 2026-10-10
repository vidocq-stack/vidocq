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
import demo.exported.PublicBean;

public final class Main {
    public static void main(String[] args) throws Exception {
        if (ModuleLayer.boot().findModule("io.vidocq.peano.processor").isPresent()) {
            throw new AssertionError("Processor must not be present at runtime");
        }
        var factory = ExpressionFactory.newInstance();
        Module app = Main.class.getModule();
        if (app.isExported("demo",factory.getClass().getModule()) || app.isExported("demo",ExpressionFactory.class.getModule())
                || !app.getDescriptor().opens().isEmpty()) {
            throw new AssertionError("Model package must remain closed");
        }
        var context = new StandardELContext(factory);
        var bean = new Bean();
        context.getVariableMapper().setVariable("bean",factory.createValueExpression(bean,Object.class));
        var name = factory.createValueExpression(context,"${bean.name}",Object.class);
        if (name.getType(context)!=String.class || name.isReadOnly(context) || bean.reads()!=0) {
            throw new AssertionError("Metadata must not evaluate the getter");
        }
        if (!"Peano".equals(name.getValue(context)) || bean.reads()!=1) throw new AssertionError("Getter failed");
        name.setValue(context,"changed");
        var count = factory.createValueExpression(context,"${bean.count}",Object.class);
        count.setValue(context,"42");
        if (bean.getCount()!=42 || count.getType(context)!=int.class) throw new AssertionError("Setter conversion failed");
        var inherited = factory.createValueExpression(context,"${bean.inherited}",Object.class);
        inherited.setValue(context,"8");
        if (!Integer.valueOf(8).equals(inherited.getValue(context))) throw new AssertionError("Inherited property failed");
        var ready = factory.createValueExpression(context,"${bean.ready}",Object.class);
        ready.setValue(context,true);
        if (!Boolean.TRUE.equals(ready.getValue(context))) throw new AssertionError("Boolean is getter must take precedence");
        if (!"https://vidocq.dev".equals(factory.createValueExpression(context,"${bean.URL}",Object.class).getValue(context))) {
            throw new AssertionError("Acronym decapitalization failed");
        }
        if (factory.createValueExpression(context,"${bean.nullable}",Object.class).getValue(context)!=null) {
            throw new AssertionError("Resolved null fell through");
        }
        var written = factory.createValueExpression(context,"${bean.writeOnly}",Object.class);
        written.setValue(context,"written");
        if (!"written".equals(bean.written())) throw new AssertionError("Write-only setter failed");
        try {
            written.getValue(context);
            throw new AssertionError("Write-only property must not be readable");
        } catch (PropertyNotFoundException expected) {}
        try {
            factory.createValueExpression(context,"${bean.failure}",Object.class).getValue(context);
            throw new AssertionError("Getter failure must be wrapped");
        } catch (ELException expected) {
            if (!(expected.getCause() instanceof java.io.IOException)) throw new AssertionError(expected);
        }
        context.getVariableMapper().setVariable("item",factory.createValueExpression(new Item(7,null),Object.class));
        var item = factory.createValueExpression(context,"${item.value}",Object.class);
        if (!Integer.valueOf(7).equals(item.getValue(context)) || !item.isReadOnly(context) || item.getType(context)!=null) {
            throw new AssertionError("Record accessor failed");
        }
        try {
            item.setValue(context,1);
            throw new AssertionError("Record must remain read-only");
        } catch (PropertyNotWritableException expected) {}
        context.getVariableMapper().setVariable("explicit",factory.createValueExpression(new Explicit(),Object.class));
        if (!Long.valueOf(42).equals(factory.createValueExpression(context,"${explicit.value}",Object.class).getValue(context))) {
            throw new AssertionError("Explicit type was not generated");
        }

        var direct = new StandardELContext(factory);
        var fallback = new StandardELContext(factory);
        fallback.addELResolver(new BeanELResolver());
        var a = new PublicBean();
        var b = new PublicBean();
        direct.getVariableMapper().setVariable("value",factory.createValueExpression(a,Object.class));
        fallback.getVariableMapper().setVariable("value",factory.createValueExpression(b,Object.class));
        for (String property : java.util.List.of("value","count","nullable")) {
            var x = factory.createValueExpression(direct,"${value."+property+"}",Object.class);
            var y = factory.createValueExpression(fallback,"${value."+property+"}",Object.class);
            if (!java.util.Objects.equals(x.getValue(direct),y.getValue(fallback))
                    || x.getType(direct)!=y.getType(fallback) || x.isReadOnly(direct)!=y.isReadOnly(fallback)) {
                throw new AssertionError("Generated property differs from the API resolver: "+property);
            }
        }
        factory.createValueExpression(direct,"${value.count}",Object.class).setValue(direct,"9");
        factory.createValueExpression(fallback,"${value.count}",Object.class).setValue(fallback,"9");
        if (a.getCount()!=b.getCount()) throw new AssertionError("Generated setter differs from the API resolver");
        var addition = factory.createMethodExpression(context,"#{bean.add(2,3)}",int.class,null);
        var reference = addition.getMethodReference(context);
        if (bean.calls()!=0 || reference.getMethodInfo().getReturnType()!=int.class
                || !java.util.Arrays.equals(reference.getEvaluatedParameters(),new Object[]{2L,3L})
                || !java.util.Arrays.equals(reference.getMethodInfo().getParamTypes(),new Class<?>[]{int.class,int.class})) {
            throw new AssertionError("Generated method metadata must preserve raw arguments without invocation");
        }
        Bean.Tag tag = java.util.Arrays.stream(reference.getAnnotations()).filter(x->x instanceof Bean.Tag)
                .map(x->(Bean.Tag)x).findFirst().orElseThrow();
        if (!tag.value().equals("math") || !java.util.Arrays.equals(tag.levels(),new int[]{2,3}) || tag.type()!=String.class) {
            throw new AssertionError("Generated runtime annotation values failed");
        }
        int[] levels = tag.levels(); levels[0]=99;
        if (tag.levels()[0]!=2) throw new AssertionError("Annotation arrays must be defensive");
        if (!Integer.valueOf(5).equals(addition.invoke(context,null)) || bean.calls()!=1) {
            throw new AssertionError("Generated inline method invocation failed");
        }
        for (Class<?> parameter : new Class<?>[]{int.class,Integer.class}) {
            var overload = factory.createMethodExpression(context,"#{bean.choose}",String.class,new Class<?>[]{parameter});
            if (overload.getMethodInfo(context).getParamTypes()[0]!=parameter
                    || !(parameter==int.class?"primitive":"wrapper").equals(overload.invoke(context,new Object[]{1L}))) {
                throw new AssertionError("Generated primitive/wrapper overload selection failed");
            }
        }
        if (!"a:b".equals(factory.createValueExpression(context,"${bean.join(':','a','b')}",Object.class).getValue(context))
                || !Integer.valueOf(6).equals(factory.createValueExpression(context,"${bean.sum(1,2,3)}",Object.class).getValue(context))) {
            throw new AssertionError("Generated varargs invocation failed");
        }
        context.getVariableMapper().setVariable("words",factory.createValueExpression(new String[]{"a","b"},Object.class));
        if (!"a:b".equals(factory.createValueExpression(context,"${bean.join(':',words)}",Object.class).getValue(context))) {
            throw new AssertionError("Packed varargs invocation failed");
        }
        context.getVariableMapper().setVariable("numbers",factory.createValueExpression(new int[]{1,2,3},Object.class));
        context.getVariableMapper().setVariable("matrix",factory.createValueExpression(new int[][]{{1},{2,3}},Object.class));
        for (String source : java.util.List.of("${bean.rows(numbers,numbers)}","${bean.rows(matrix)}")) {
            if (!Integer.valueOf(2).equals(factory.createValueExpression(context,source,Object.class).getValue(context))) {
                throw new AssertionError("Multidimensional varargs packing failed");
            }
        }
        if (!Integer.valueOf(6).equals(factory.createValueExpression(context,"${bean.sum(numbers)}",Object.class).getValue(context))) {
            throw new AssertionError("Packed primitive varargs failed");
        }
        var accepted = factory.createMethodExpression(context,"#{bean.accept('accepted')}",void.class,null);
        if (accepted.invoke(context,null)!=null || !"accepted".equals(bean.getName())) {
            throw new AssertionError("Generated void invocation failed");
        }
        context.getVariableMapper().setVariable("beanClass",factory.createValueExpression(new ELClass(Bean.class),Object.class));
        if (!"static".equals(factory.createValueExpression(context,"${beanClass.getStaticValue()}",Object.class).getValue(context))) {
            throw new AssertionError("Generated static invocation failed");
        }
        context.getFunctionMapper().mapFunction("f","value",Bean.class.getMethod("getStaticValue"));
        if (!"static".equals(factory.createValueExpression(context,"${f:value()}",Object.class).getValue(context))) {
            throw new AssertionError("Generated mapped static invocation failed");
        }
        try {
            factory.createMethodExpression(context,"#{bean.add(1,2)}",String.class,null).invoke(context,null);
            throw new AssertionError("Generated return mismatch must fail before invoking");
        } catch (MethodNotFoundException expected) {}
        try {
            factory.createValueExpression(context,"${bean.ambiguous('value')}",Object.class).getValue(context);
            throw new AssertionError("Unrelated overloads must remain ambiguous");
        } catch (MethodNotFoundException expected) {}
        try {
            factory.createValueExpression(context,"${bean.broken()}",Object.class).getValue(context);
            throw new AssertionError("Generated method failure must be wrapped");
        } catch (ELException expected) {
            if (!(expected.getCause() instanceof java.io.IOException)) throw new AssertionError(expected);
        }
        var x = factory.createMethodExpression(direct,"#{value.add(2,3)}",int.class,null);
        var y = factory.createMethodExpression(fallback,"#{value.add(2,3)}",int.class,null);
        var xr = x.getMethodReference(direct);
        var yr = y.getMethodReference(fallback);
        if (!x.invoke(direct,null).equals(y.invoke(fallback,null))
                || xr.getMethodInfo().getReturnType()!=yr.getMethodInfo().getReturnType()
                || !java.util.Arrays.equals(xr.getMethodInfo().getParamTypes(),yr.getMethodInfo().getParamTypes())) {
            throw new AssertionError("Generated invocation differs from API fallback");
        }
        var reflected = PublicBean.class.getMethod("add",int.class,int.class);
        for (var annotation : xr.getAnnotations()) {
            var other = java.util.Arrays.stream(reflected.getAnnotations())
                    .filter(z->z.annotationType()==annotation.annotationType()).findFirst().orElseThrow();
            if (!annotation.equals(other) || !other.equals(annotation) || annotation.hashCode()!=other.hashCode()) {
                throw new AssertionError("Generated annotation literal differs from runtime annotation");
            }
        }
        System.out.println("PEANO_PROPERTIES_OK");
    }
}

