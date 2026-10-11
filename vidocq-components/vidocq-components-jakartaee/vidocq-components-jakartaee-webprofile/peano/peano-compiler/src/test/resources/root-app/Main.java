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
import java.util.*;

public class Main {
    static class Context extends ELContext {
        final Map<String,Object> values=new HashMap<>();
        final List<String> calls=new ArrayList<>();
        final Map<String,ValueExpression> bindings=new HashMap<>();
        int captures;
        final ELResolver resolver=new ELResolver() {
            public Object getValue(ELContext c,Object b,Object p) {
                calls.add("read:"+p);
                if (b==null && values.containsKey(p)) { c.setPropertyResolved(b,p); return values.get(p); }
                return null;
            }
            public Class<?> getType(ELContext c,Object b,Object p) {
                calls.add("type:"+p);
                if (b==null && values.containsKey(p)) { c.setPropertyResolved(b,p); return Integer.class; }
                return null;
            }
            public boolean isReadOnly(ELContext c,Object b,Object p) {
                calls.add("readonly:"+p);
                if (b==null && values.containsKey(p)) c.setPropertyResolved(b,p);
                return false;
            }
            public void setValue(ELContext c,Object b,Object p,Object v) {
                calls.add("write:"+p);
                if (b==null && values.containsKey(p)) { c.setPropertyResolved(b,p); values.put((String)p,v); }
            }
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return String.class; }
        };
        public ELResolver getELResolver() { return resolver; }
        public FunctionMapper getFunctionMapper() { return null; }
        public VariableMapper getVariableMapper() {
            return new VariableMapper() {
                public ValueExpression resolveVariable(String name) { captures++; return bindings.get(name); }
                public ValueExpression setVariable(String name,ValueExpression value) { return bindings.put(name,value); }
            };
        }
        @SuppressWarnings("unchecked")
        public <T> T convertToType(Object value,Class<T> type) {
            if (type==Object.class) return (T)value;
            if (type==String.class) return (T)(value==null ? "" : value.toString());
            return type.cast(value);
        }
    }
    static final class Binding extends ValueExpression {
        private static final long serialVersionUID=1;
        Object value;
        Binding(Object value) { this.value=value; }
        public <T> T getValue(ELContext c) { return (T)value; }
        public void setValue(ELContext c,Object value) { this.value=value; }
        public boolean isReadOnly(ELContext c) { return false; }
        public Class<?> getType(ELContext c) { return Number.class; }
        public ValueReference getValueReference(ELContext c) { return new ValueReference("binding","value"); }
        public Class<?> getExpectedType() { return Object.class; }
        public String getExpressionString() { return "binding"; }
        public boolean isLiteralText() { return false; }
        public boolean equals(Object o) { return o instanceof Binding b && Objects.equals(value,b.value); }
        public int hashCode() { return Objects.hashCode(value); }
    }
    static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(ModuleLayer.boot().findModule("io.vidocq.peano.core").isEmpty()
                && ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isEmpty(),"Build modules resolved");
        var context=new Context();
        context.values.put("value",null);
        var root=new Root(Object.class);
        context.setPropertyResolved(true);
        check(root.getValue(context)==null && context.isPropertyResolved(),"Claimed null lost");
        context.calls.clear();
        check(root.getType(context)==Integer.class && context.calls.equals(List.of("type:value")),"Metadata read value");
        context.calls.clear();
        root.setValue(context,"uncoerced");
        check(context.values.get("value").equals("uncoerced")
                && context.calls.equals(List.of("readonly:value","write:value")),"Root write converted or read");
        check(!root.isReadOnly(context),"Writable root rejected");
        context.calls.clear();
        ValueReference ref=root.getValueReference(context);
        check(ref.getBase()==null && ref.getProperty().equals("value")
                && context.calls.equals(List.of("readonly:value")),"Root reference read getter");
        var events=new ArrayList<String>();
        context.addEvaluationListener(new EvaluationListener() {
            public void beforeEvaluation(ELContext c,String s) { events.add("before"); }
            public void afterEvaluation(ELContext c,String s) { events.add("after"); }
        });
        check(new Root(String.class).getValue(context).equals("uncoerced")
                && events.equals(List.of("before","after")),"Root notifications/conversion mismatch");
        events.clear();
        try { new Missing(Object.class).getValue(context); throw new AssertionError("Missing root accepted"); }
        catch (PropertyNotFoundException expected) {}
        check(events.equals(List.of("before")),"Failed root notified completion");
        var original=new Binding(7);
        context.bindings.put("value",original);
        var captured=new Root(context,Object.class);
        check(context.captures==1,"Binding not captured exactly once");
        context.bindings.put("value",new Binding(8));
        var other=new Context();
        other.values.put("value",99);
        check(captured.getValue(other).equals(7) && captured.getType(other)==Number.class,"Binding was remapped");
        check(captured.getValueReference(other).getBase().equals("binding"),"Binding reference lost");
        captured.setValue(other,9);
        check(original.value.equals(9) && !captured.isReadOnly(other),"Binding write lost");
        check(!captured.equals(root) && captured.equals(new Root(contextFor(original),Object.class)),"Binding identity mismatch");
        other.enterLambdaScope(Map.of("value",10));
        check(captured.getValue(other).equals(10) && captured.getType(other)==Integer.class
                && captured.isReadOnly(other) && captured.getValueReference(other)==null,"Lambda precedence mismatch");
        try { captured.setValue(other,0); throw new AssertionError("Lambda root writable"); }
        catch (PropertyNotWritableException expected) {}
        other.exitLambdaScope();
        var nullScope=new HashMap<String,Object>(); nullScope.put("value",null);
        other.enterLambdaScope(nullScope);
        check(captured.getValue(other)==null && captured.getType(other)==null,"Null lambda argument lost");
        other.exitLambdaScope();
        var initiallyUnbound=new Root(other,Object.class);
        other.bindings.put("value",new Binding(55));
        check(initiallyUnbound.getValue(other).equals(99),"Absent binding was captured later");
        context.bindings.put("value",new Binding(null));
        check(new Root(context,Object.class).getValue(other)==null,"Claimed null binding lost");
        try { new Root(null,Object.class); throw new AssertionError("Null creation context accepted"); }
        catch (NullPointerException expected) {}
        var bytes=new java.io.ByteArrayOutputStream();
        try (var output=new java.io.ObjectOutputStream(bytes)) { output.writeObject(captured); }
        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            var restored=(ValueExpression)input.readObject();
            check(restored.equals(captured) && restored.hashCode()==captured.hashCode()
                    && restored.getValue(other).equals(9),"Captured serialization mismatch");
        }
        var noMapper=new Context() { public VariableMapper getVariableMapper() { return null; } };
        noMapper.values.put("value",3);
        check(new Root(noMapper,Object.class).getValue(noMapper).equals(3),"Null mapper failed");
        var failure=new Context() { public ELResolver getELResolver() { throw new IllegalStateException("resolver"); } };
        try { root.getValue(failure); throw new AssertionError("Resolver failure swallowed"); }
        catch (ELException expected) { check(expected.getCause() instanceof IllegalStateException,"Resolver cause lost"); }
        context.getImportHandler().importClass("java.lang.Math");
        context.getImportHandler().importStatic("java.lang.Math.PI");
        check(new ImportedClass(Object.class).getValue(context) instanceof ELClass
                && new ImportedClass(Object.class).getType(context)==ELClass.class
                && new ImportedClass(Object.class).isReadOnly(context)
                && new ImportedClass(Object.class).getValueReference(context)==null,"Imported class metadata lost");
        var imported=new Context() {
            final CompositeELResolver chain=new CompositeELResolver();
            { chain.add(resolver); chain.add(new StaticFieldELResolver()); }
            public ELResolver getELResolver() { return chain; }
        };
        imported.getImportHandler().importStatic("java.lang.Math.PI");
        check(new ImportedField(Object.class).getValue(imported).equals(Math.PI)
                && String.valueOf(new ImportedField(Object.class).getType(imported)).equals(args[0]),"Imported field resolution lost");
        System.out.println("PEANO_COMPILED_ROOT_OK");
    }
    static Context contextFor(ValueExpression expression) {
        var context=new Context(); context.bindings.put("value",expression); return context;
    }
}

