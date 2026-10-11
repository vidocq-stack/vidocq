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
        final Map<String,Object> roots=new HashMap<>();
        final List<String> effects=new ArrayList<>();
        final List<String> captures=new ArrayList<>();
        final List<String> variableCaptures=new ArrayList<>();
        public ELResolver getELResolver() { return new ELResolver() {
            public Object getValue(ELContext c,Object b,Object p) {
                effects.add("read:"+p);
                if (b==null && roots.containsKey(p)) { c.setPropertyResolved(b,p); return roots.get(p); }
                return null;
            }
            public Class<?> getType(ELContext c,Object b,Object p) { return null; }
            public boolean isReadOnly(ELContext c,Object b,Object p) { return true; }
            public void setValue(ELContext c,Object b,Object p,Object value) {}
            public Object invoke(ELContext c,Object b,Object p,Class<?>[] t,Object[] a) {
                check(!c.isPropertyResolved() && t==null,"Imported invocation flags changed");
                if (b instanceof ELClass owner && owner.getKlass()==Constructed.class && "<init>".equals(p)) {
                    c.setPropertyResolved(b,p); return new Constructed(c.convertToType(a[0],Long.class));
                }
                if (b instanceof ELClass owner && owner.getKlass()==Functions.class && "sum".equals(p)) {
                    c.setPropertyResolved(b,p); return Functions.sum(c.convertToType(a[0],Long.class),c.convertToType(a[1],Long.class));
                }
                throw new AssertionError("Mapped function used custom resolver chain");
            }
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return null; }
        }; }
        public VariableMapper getVariableMapper() { return new VariableMapper() {
            public ValueExpression resolveVariable(String name) { variableCaptures.add(name); return null; }
            public ValueExpression setVariable(String name,ValueExpression value) { throw new UnsupportedOperationException(); }
        }; }
        public FunctionMapper getFunctionMapper() { return new FunctionMapper() {
            public Method resolveFunction(String prefix,String name) { String key=prefix+":"+name; captures.add(key); return functions.get(key); }
        }; }
        public <T> T convertToType(Object value,Class<T> type) {
            if (type==Object.class) return (T)value;
            if (type==boolean.class || type==Boolean.class) return (T)Boolean.valueOf(String.valueOf(value));
            if (type==Long.class || type==long.class) { effects.add("long:"+value); return (T)Long.valueOf(value.toString()); }
            if (type==String.class) { effects.add("string:"+value); return (T)(value==null ? "" : value.toString()); }
            return type.cast(value);
        }
    }
    static LambdaExpression lambda(Context context,List<String> names,boolean constant) {
        var body=new ValueExpression() {
            public <T> T getValue(ELContext c) { return (T)(names.isEmpty() ? context.roots.get("callable")
                    : constant ? "lambda" : Long.valueOf(((Number)c.getLambdaArgument("x")).longValue()+10)); }
            public void setValue(ELContext c,Object value) { throw new PropertyNotWritableException(); }
            public boolean isReadOnly(ELContext c) { return true; }
            public Class<?> getType(ELContext c) { return Object.class; }
            public Class<?> getExpectedType() { return Object.class; }
            public String getExpressionString() { return "lambda"; }
            public boolean isLiteralText() { return false; }
            public boolean equals(Object other) { return this==other; }
            public int hashCode() { return System.identityHashCode(this); }
        };
        var lambda=new LambdaExpression(names,body); lambda.setELContext(context); return lambda;
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
        context.roots.put("callable",lambda(context,List.of("x"),false)); context.roots.put("nil",null);
        context.roots.put("factory",lambda(context,List.of(),false));
        check(new Named(context,Object.class).getValue(context).equals(12L),"Named lambda callable failed");
        context.functions.put(":mapped",Functions.class.getMethod("sum",long.class,long.class));
        var mapped=new Mapped(context,Object.class);
        context.functions.put(":mapped",Functions.class.getMethod("alternate",long.class,long.class));
        context.roots.put("mapped",lambda(context,List.of("a","b"),true));
        check(mapped.getValue(context).equals("lambda"),"Lambda did not precede mapped function");
        context.roots.put("mapped",0L);
        check(mapped.getValue(context).equals(3L),"Default-prefix function remapped after creation");
        check(new GeneralLambda(Object.class).getValue(context).equals(13L),"General lambda target failed");
        context.getImportHandler().importClass("demo.Constructed");
        Object constructed=new Construct(Object.class).getValue(context);
        check(constructed instanceof Constructed object && object.value==7L,"Imported constructor did not use direct resolver");
        context.getImportHandler().importStatic("demo.Main$Functions.sum");
        check(new Imported(Object.class).getValue(context).equals(3L),"Imported static callable failed");
        if (coreFallback) {
            context.functions.put(":mapped",Functions.class.getMethod("sum",long.class,long.class));
            var factory=ExpressionFactory.newInstance();
            for (ValueExpression compiled:List.of(new Named(context,Object.class),new Mapped(context,Object.class),
                    new GeneralLambda(context,Object.class),new Construct(context,Object.class),new Imported(context,Object.class))) {
                var interpreted=factory.createValueExpression(context,compiled.getExpressionString(),Object.class);
                Object actual=compiled.getValue(context),expected=interpreted.getValue(context);
                check(actual instanceof Constructed object ? expected instanceof Constructed other && object.value==other.value
                        : Objects.equals(actual,expected),"Named-call differential mismatch: "+compiled.getExpressionString());
            }
        }
        for (ValueExpression expression:List.of(new InvalidCallable(Object.class),new UnresolvedCallable(Object.class),new NullCallable(Object.class))) {
            try { expression.getValue(context); throw new AssertionError("Noncallable target accepted or evaluated args"); }
            catch (MethodNotFoundException expected) {}
            if (coreFallback) {
                try { ExpressionFactory.newInstance().createValueExpression(context,expression.getExpressionString(),Object.class).getValue(context);
                    throw new AssertionError("Oracle accepted invalid callable"); }
                catch (MethodNotFoundException expected) {}
            }
        }
        context.variableCaptures.clear();
        var lambdaExpression=new Lambda(context,Object.class);
        check(context.variableCaptures.isEmpty(),"Lambda parameters captured from VariableMapper");
        var compiledLambda=(LambdaExpression)lambdaExpression.getValue(context);
        check(compiledLambda.invoke(context,5L).equals(6L),"Compiled lambda body failed");
        check(new Immediate(Object.class).getValue(context).equals(3L),"Immediate lambda failed");
        check(new Closure(Object.class).getValue(context).equals(7L),"Lexical closure failed");
        check(new Shadow(Object.class).getValue(context).equals(3L),"Nested parameter shadow failed");
        check(new NullableClosure(Object.class).getValue(context)==null,"Nullable capture failed");
        context.functions.put(":callable",Functions.class.getDeclaredMethod("hidden"));
        context.captures.clear(); context.variableCaptures.clear();
        check(new LocalCall(context,Object.class).getValue(context).equals(12L),"Local callable parameter failed");
        check(context.captures.isEmpty() && context.variableCaptures.equals(List.of("callable")),"Local parameter mapping was captured");
        var closureExpression=new Closure(context,Object.class);
        var closureBytes=new java.io.ByteArrayOutputStream();
        try (var output=new java.io.ObjectOutputStream(closureBytes)) { output.writeObject(closureExpression); }
        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(closureBytes.toByteArray()))) {
            var restored=(ValueExpression)input.readObject();
            check(restored.equals(closureExpression) && restored.getValue(context).equals(7L),"Compiled closure expression restoration failed");
        }
        try { new LambdaFailure(Object.class).getValue(context); throw new AssertionError("Lambda failure swallowed"); }
        catch (ELException expected) { check(!context.isLambdaArgument("x"),"Failed lambda leaked scope"); }
        if (coreFallback) {
            for (ValueExpression compiled:List.of(new Immediate(context,Object.class),new Closure(context,Object.class),
                    new Shadow(context,Object.class),new NullableClosure(context,Object.class),new LocalCall(context,Object.class))) {
                Object expected=ExpressionFactory.newInstance().createValueExpression(context,compiled.getExpressionString(),Object.class).getValue(context);
                check(Objects.equals(compiled.getValue(context),expected),"Lambda differential mismatch: "+compiled.getExpressionString());
            }
        }
        System.out.println("PEANO_COMPILED_FUNCTION_OK");
    }
}

