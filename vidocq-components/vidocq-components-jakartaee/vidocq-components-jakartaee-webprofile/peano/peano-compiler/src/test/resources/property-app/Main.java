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
        final Map<String,Object> roots=new HashMap<>();
        final Map<String,ValueExpression> bindings=new HashMap<>();
        final List<String> calls=new ArrayList<>();
        final List<String> captures=new ArrayList<>();
        final ELResolver resolver=new ELResolver() {
            public Object getValue(ELContext c,Object b,Object p) {
                calls.add("read:"+p);
                Map<?,?> values=b==null ? roots : b instanceof Map<?,?> m ? m : Map.of();
                if (values.containsKey(p)) { c.setPropertyResolved(b,p); return values.get(p); }
                return null;
            }
            public Class<?> getType(ELContext c,Object b,Object p) {
                calls.add("type:"+p);
                if (b instanceof Map<?,?> m && m.containsKey(p)) c.setPropertyResolved(b,p);
                return Long.class;
            }
            public boolean isReadOnly(ELContext c,Object b,Object p) {
                calls.add("readonly:"+p);
                if (b instanceof Map<?,?> m && m.containsKey(p)) c.setPropertyResolved(b,p);
                return false;
            }
            public void setValue(ELContext c,Object b,Object p,Object v) {
                calls.add("write:"+p);
                if (b instanceof Map m && m.containsKey(p)) { c.setPropertyResolved(b,p); m.put(p,v); }
            }
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return Object.class; }
            public Object invoke(ELContext c,Object base,Object method,Class<?>[] types,Object[] arguments) {
                calls.add("invoke:"+method);
                check(!c.isPropertyResolved() && types==null,"Invocation flags or declared parameter types changed");
                if (base instanceof Model model) {
                    if ("sum".equals(method)) { c.setPropertyResolved(base,method); return model.sum(arguments); }
                    if ("box".equals(method)) { c.setPropertyResolved(base,method); return model.box(); }
                    if ("nothing".equals(method)) { c.setPropertyResolved(base,method); return null; }
                    if ("boom".equals(method)) { c.setPropertyResolved(base,method); throw new IllegalStateException("model failure"); }
                }
                return null;
            }
        };
        public ELResolver getELResolver() { return resolver; }
        public FunctionMapper getFunctionMapper() { return null; }
        public VariableMapper getVariableMapper() {
            return new VariableMapper() {
                public ValueExpression resolveVariable(String name) { captures.add(name); return bindings.get(name); }
                public ValueExpression setVariable(String name,ValueExpression value) { return bindings.put(name,value); }
            };
        }
        public <T> T convertToType(Object value,Class<T> type) {
            if (type==Object.class) return (T)value;
            if (type==boolean.class || type==Boolean.class) return (T)Boolean.valueOf(String.valueOf(value));
            if (type==Long.class) { calls.add("convert:"+value); return (T)Long.valueOf(value.toString()); }
            if (type==String.class) { calls.add("string:"+value); return (T)(value==null ? "" : value.toString()); }
            return type.cast(value);
        }
    }
    static final class Model {
        long sum(Object[] values) { return ((Number)values[0]).longValue()+((Number)values[1]).longValue(); }
        Map<String,Object> box() { return new HashMap<>(Map.of("value",42L)); }
    }
    static final class Binding extends ValueExpression {
        private static final long serialVersionUID=1;
        final Object value;
        Binding(Object value) { this.value=value; }
        public <T> T getValue(ELContext c) { return (T)value; }
        public void setValue(ELContext c,Object v) { throw new PropertyNotWritableException(); }
        public boolean isReadOnly(ELContext c) { return true; }
        public Class<?> getType(ELContext c) { return value==null ? null : value.getClass(); }
        public String getExpressionString() { return "binding"; }
        public Class<?> getExpectedType() { return Object.class; }
        public boolean isLiteralText() { return false; }
        public boolean equals(Object o) { return o instanceof Binding b && Objects.equals(value,b.value); }
        public int hashCode() { return Objects.hashCode(value); }
    }
    static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(ModuleLayer.boot().findModule("io.vidocq.peano.core").isEmpty()
                && ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isEmpty(),"Build modules resolved");
        var context=new Context();
        var child=new HashMap<String,Object>(); child.put("value",null);
        var bean=new HashMap<String,Object>(); bean.put("value",7L); bean.put("child",child);
        context.roots.put("bean",bean); context.roots.put("nil",null); context.roots.put("key","value");
        var property=new Property(Object.class);
        check(property.getValue(context).equals(7L),"Property value failed");
        context.calls.clear();
        check(property.getType(context)==Long.class && context.calls.equals(List.of("read:bean","type:value")),
                "Metadata read final getter: "+context.calls);
        context.calls.clear();
        check(!property.isReadOnly(context) && context.calls.equals(List.of("read:bean","readonly:value")),"Read-only getter read");
        context.calls.clear();
        var reference=property.getValueReference(context);
        check(reference.getBase()==bean && reference.getProperty().equals("value")
                && context.calls.equals(List.of("read:bean")),"Reference getter read");
        context.calls.clear();
        property.setValue(context,"9");
        check(bean.get("value").equals(9L)
                && context.calls.equals(List.of("read:bean","type:value","convert:9","write:value")),"Property write ordering: "+context.calls);
        context.calls.clear();
        check(new Nested(Object.class).getValue(context)==null && context.isPropertyResolved(),"Nested claimed null lost");
        context.calls.clear();
        check(new NullBase(Object.class).getValue(context)==null && context.calls.equals(List.of("read:nil")),"Null base read key");
        context.calls.clear();
        try { new NullBase(Object.class).getType(context); throw new AssertionError("Null metadata base accepted"); }
        catch (PropertyNotFoundException expected) {}
        check(context.calls.equals(List.of("read:nil")),"Null metadata base read key");
        check(new NullKey(Object.class).getValue(context)==null,"Null key read failed");
        try { new NullKey(Object.class).setValue(context,7); throw new AssertionError("Null write key accepted"); }
        catch (PropertyNotFoundException expected) {}
        check(new LiteralKey(Object.class).getValue(context).equals(9L),"Literal key failed");
        check(property.equals(new LiteralKey(Object.class)),"Equivalent navigation identity changed");
        check(new ComputedKey(Object.class).getValue(context).equals(9L),"Computed key failed");
        context.calls.clear();
        var composite=new Composite(Object.class);
        check(composite.getType(context)==String.class && context.calls.isEmpty(),"Composite metadata evaluated parts");
        check(composite.getValue(context).equals("value=9;value"),"Composite bound parts failed");
        context.calls.clear();
        check(new Conditional(Object.class).getValue(context).equals(9L)
                && context.calls.equals(List.of("read:bean","read:value")),"Discarded conditional resolved name");
        context.calls.clear();
        check(Boolean.TRUE.equals(new Logical(Object.class).getValue(context)) && context.calls.isEmpty(),"Discarded logical resolved name");
        context.roots.put("array",new int[0]);
        check(Boolean.TRUE.equals(new BoundEmpty(Object.class).getValue(context)),"Empty primitive array failed");
        context.roots.put("array",new Object[]{null});
        check(Boolean.FALSE.equals(new BoundEmpty(Object.class).getValue(context)),"Nonempty reference array failed");
        check(new BoundList(Object.class).getValue(context).equals(List.of(9L,"value")),"Bound collection failed");
        check(new BoundConcat(Object.class).getValue(context).equals("9value"),"Bound concat failed");
        check(new BoundSequence(Object.class).getValue(context).equals("value"),"Bound sequence failed");
        check(composite.isReadOnly(context) && composite.getValueReference(context)==null,"Readonly body metadata changed");
        try { composite.setValue(context,0); throw new AssertionError("Composite body writable"); }
        catch (PropertyNotWritableException expected) {}
        context.roots.put("model",new Model()); context.roots.put("methodName","sum");
        context.calls.clear();
        var method=new MethodCall(Object.class);
        check(method.getValue(context).equals(11L)
                && context.calls.equals(List.of("read:model","string:sum","read:bean","read:value","invoke:sum")),
                "Method operand/key/argument order: "+context.calls);
        context.calls.clear();
        check(method.getType(context)==Long.class
                && context.calls.equals(List.of("read:model","string:sum","read:bean","read:value","invoke:sum")),
                "Method metadata changed effects: "+context.calls);
        context.calls.clear();
        check(method.isReadOnly(context) && method.getValueReference(context)==null && context.calls.isEmpty(),"Call location metadata evaluated body");
        context.calls.clear();
        check(new MethodProperty(Object.class).getType(context)==Long.class
                && context.calls.equals(List.of("read:model","string:box","invoke:box","type:value")),"Call parent metadata read getter: "+context.calls);
        context.calls.clear();
        check(new NullCall(Object.class).getValue(context)==null && context.calls.equals(List.of("read:nil")),"Null call evaluated discarded arguments");
        context.calls.clear();
        check(new NullCallKey(Object.class).getValue(context)==null
                && context.calls.equals(List.of("read:model","read:nil")),"Null call key evaluated arguments");
        check(new DynamicMethod(Object.class).getValue(context).equals(3L),"Dynamic method name failed");
        context.calls.clear();
        check(new CallNullParent(Object.class).getValue(context)==null
                && context.calls.equals(List.of("read:model","string:nothing","invoke:nothing")),"Null method parent read key");
        try { new UnclaimedMethod(Object.class).getValue(context); throw new AssertionError("Unclaimed inline method accepted"); }
        catch (MethodNotFoundException expected) {}
        try { new FailingMethod(Object.class).getValue(context); throw new AssertionError("Model failure swallowed"); }
        catch (ELException expected) { check(expected.getCause() instanceof IllegalStateException,"Method cause lost"); }
        context.bindings.put("bean",new Binding(bean)); context.bindings.put("key",new Binding("value"));
        var captured=new Indexed(context,Object.class);
        check(context.captures.equals(List.of("bean","key")),"Capture order or duplicates: "+context.captures);
        context.bindings.put("key",new Binding("child")); context.roots.put("bean",Map.of());
        check(captured.getValue(context).equals(9L),"Path bindings remapped");
        context.captures.clear(); bean.put("key","value");
        check(new DuplicateRoots(context,Object.class).getValue(context).equals(9L)
                && context.captures.equals(List.of("bean")),"Duplicate root capture: "+context.captures);
        var events=new ArrayList<String>();
        context.addEvaluationListener(new EvaluationListener() {
            public void beforeEvaluation(ELContext c,String s) { events.add("before"); }
            public void afterEvaluation(ELContext c,String s) { events.add("after"); }
        });
        captured.getValue(context); check(events.equals(List.of("before","after")),"Path evaluation events");
        var bytes=new java.io.ByteArrayOutputStream();
        try (var output=new java.io.ObjectOutputStream(bytes)) { output.writeObject(captured); }
        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            var restored=(ValueExpression)input.readObject();
            check(restored.equals(captured) && restored.hashCode()==captured.hashCode()
                    && restored.getValue(context).equals(9L),"Path serialization lost");
        }
        check(!captured.equals(new Indexed(Object.class)),"Path binding identity ignored");
        var capturedMethod=new MethodCall(context,Object.class);
        var methodBytes=new java.io.ByteArrayOutputStream();
        try (var output=new java.io.ObjectOutputStream(methodBytes)) { output.writeObject(capturedMethod); }
        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(methodBytes.toByteArray()))) {
            var restored=(ValueExpression)input.readObject();
            check(restored.equals(capturedMethod) && restored.getValue(context).equals(11L),"Method argument bindings lost in serialization");
        }
        var capturedComposite=new Composite(context,Object.class);
        var compositeBytes=new java.io.ByteArrayOutputStream();
        try (var output=new java.io.ObjectOutputStream(compositeBytes)) { output.writeObject(capturedComposite); }
        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(compositeBytes.toByteArray()))) {
            var restored=(ValueExpression)input.readObject(); context.calls.clear();
            check(restored.equals(capturedComposite) && restored.getType(context)==String.class && context.calls.isEmpty(),
                    "Serialized composite metadata changed");
            check(restored.getValue(context).equals("value=9;child"),"Serialized composite bindings lost");
        }
        var failure=new Context() { public ELResolver getELResolver() { throw new IllegalStateException("resolver"); } };
        try { property.getValue(failure); throw new AssertionError("Path resolver failure swallowed"); }
        catch (ELException expected) { check(expected.getCause() instanceof IllegalStateException,"Path resolver cause lost"); }
        System.out.println("PEANO_COMPILED_PROPERTY_OK");
    }
}

