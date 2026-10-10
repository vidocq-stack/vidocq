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
    public static void main(String[] args) {
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
        System.out.println("PEANO_PROPERTIES_OK");
    }
}

