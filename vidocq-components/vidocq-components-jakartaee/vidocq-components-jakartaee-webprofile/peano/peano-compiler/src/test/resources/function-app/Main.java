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
import java.lang.reflect.Method;
import java.util.*;
public class Main {
    public static final class Functions {
        static final ELException failure=new ELException("original");
        public static long sum(long a,long b) { return a+b; }
        public static long alternate(long a,long b) { return a+b+1000; }
        public static String none() { return null; }
        public static long boom() { throw new IllegalStateException("function failure"); }
        public static void original() { throw failure; }
        public static String join(String... values) { return String.join("",values); }
        private static long hidden() { return 0; }
    }
    static final class Context extends ELContext {
        final Map<String,Method> functions=new HashMap<>();
        final List<String> effects=new ArrayList<>();
        final List<String> captures=new ArrayList<>();
        public ELResolver getELResolver() { return new ELResolver() {
            public Object getValue(ELContext c,Object b,Object p) { return null; }
            public Class<?> getType(ELContext c,Object b,Object p) { return null; }
            public boolean isReadOnly(ELContext c,Object b,Object p) { return true; }
            public void setValue(ELContext c,Object b,Object p,Object value) {}
            public Object invoke(ELContext c,Object b,Object p,Class<?>[] t,Object[] a) { throw new AssertionError("Mapped function used custom resolver chain"); }
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return null; }
        }; }
        public VariableMapper getVariableMapper() { return null; }
        public FunctionMapper getFunctionMapper() { return new FunctionMapper() {
            public Method resolveFunction(String prefix,String name) { String key=prefix+":"+name; captures.add(key); return functions.get(key); }
        }; }
        public <T> T convertToType(Object value,Class<T> type) {
            if (type==Object.class) return (T)value;
            if (type==Long.class || type==long.class) { effects.add("long:"+value); return (T)Long.valueOf(value.toString()); }
            if (type==String.class) { effects.add("string:"+value); return (T)(value==null ? "" : value.toString()); }
            return type.cast(value);
        }
    }
    static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        boolean coreFallback=args.length>0;
        check(ModuleLayer.boot().findModule("io.vidocq.peano.core").isPresent()==coreFallback
                && ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isEmpty(),"Unexpected runtime modules");
        var context=new Context();
        for (String name:List.of("sum","none","boom","original","join")) {
            Class<?>[] params=name.equals("sum") ? new Class<?>[]{long.class,long.class}
                    : name.equals("join") ? new Class<?>[]{String[].class} : new Class<?>[0];
            context.functions.put("f:"+name,Functions.class.getMethod(name,params));
        }
        var sum=new Sum(context,Object.class);
        check(context.captures.equals(List.of("f:sum")),"Mapping not captured once");
        context.functions.put("f:sum",Functions.class.getMethod("alternate",long.class,long.class));
        context.effects.clear();
        check(sum.getValue(context).equals(9L)
                && context.effects.equals(List.of("table:sum","long:7","long:2")),"Mapped function changed or conversion order: "+context.effects);
        check(sum.getType(context)==Long.class && sum.isReadOnly(context) && sum.getValueReference(context)==null,"Function metadata failed");
        check(Provider.creations==1,"Accessor providers recreated for the same evaluation context");
        ClassLoader previousLoader=Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(previousLoader) {});
            check(sum.getValue(context).equals(9L) && Provider.creations==2,"Provider snapshot ignored loader change");
            check(sum.getValue(context).equals(9L) && Provider.creations==2,"Changed-loader snapshot was not reused");
        } finally { Thread.currentThread().setContextClassLoader(previousLoader); }
        context.captures.clear();
        check(new Nested(context,Object.class).getValue(context).equals(2006L)
                && context.captures.equals(List.of("f:sum")),"Nested function capture or binding failed");
        check(new None(context,Object.class).getValue(context)==null && context.isPropertyResolved(),"Function claimed null lost");
        check(new Join(context,Object.class).getValue(context).equals("ab"),"Varargs binding failed");
        try { new Boom(context,Object.class).getValue(context); throw new AssertionError("Function failure swallowed"); }
        catch (ELException expected) { check(expected.getCause() instanceof IllegalStateException,"Function cause lost"); }
        try { new Original(context,Object.class).getValue(context); throw new AssertionError("Original EL failure swallowed"); }
        catch (ELException expected) { check(expected==Functions.failure,"EL failure wrapped"); }
        try { new Missing(context,Object.class); throw new AssertionError("Discarded unmapped function not rejected at creation"); }
        catch (ELException expected) {}
        try { new Sum(Object.class); throw new AssertionError("Mapper-free function instantiated"); }
        catch (ELException expected) {}
        var first=new Sum(context,Object.class);
        check(!first.equals(sum),"Function binding identity ignored mapping");
        context.functions.put("f:sum",Functions.class.getMethod("sum",long.class,long.class));
        check(new Sum(context,Object.class).equals(sum),"Equivalent descriptor identity changed");
        context.functions.put("f:sum",Functions.class.getDeclaredMethod("hidden"));
        try { new Sum(context,Object.class); throw new AssertionError("Private function accepted"); }
        catch (ELException expected) {}
        context.functions.put("f:sum",Functions.class.getMethod("sum",long.class,long.class));
        var bytes=new java.io.ByteArrayOutputStream();
        try (var output=new java.io.ObjectOutputStream(bytes)) { output.writeObject(sum); }
        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            var restored=(ValueExpression)input.readObject();
            check(restored.equals(sum) && restored.hashCode()==sum.hashCode()
                    && restored.getValue(context).equals(9L),"Function descriptor serialization failed");
        }
        context.functions.put("f:abs",Math.class.getMethod("abs",long.class));
        var fallback=new Fallback(context,Object.class);
        if ("deny".equals(System.getProperty("peano.reflection"))) {
            try { fallback.getValue(context); throw new AssertionError("Unregistered function fallback allowed"); }
            catch (ELException expected) { check(expected.getMessage().contains("Reflection fallback denied"),"Unexpected fallback failure"); }
        } else if (coreFallback) check(fallback.getValue(context).equals(7L),"API static fallback failed");
        System.out.println("PEANO_COMPILED_FUNCTION_OK");
    }
}

