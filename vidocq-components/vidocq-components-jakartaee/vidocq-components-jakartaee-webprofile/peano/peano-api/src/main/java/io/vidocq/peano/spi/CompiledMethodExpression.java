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

package io.vidocq.peano.spi;

import java.util.*;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import jakarta.el.*;

/** Method protocol for classes emitted at build time, without a runtime syntax tree. */
public abstract class CompiledMethodExpression extends MethodExpression {
    private static final long serialVersionUID=1L;
    private final String source;
    private final Class<?> expectedReturn;
    private final Class<?>[] parameterTypes;
    private final int shape;
    private final Bridge bridge;

    protected CompiledMethodExpression(String source,String identity,ELContext creationContext,
            Class<?> expectedReturn,Class<?>[] parameterTypes,int shape,String[] names,String[] functions) {
        this.source=Objects.requireNonNull(source,"source");
        this.expectedReturn=expectedReturn;
        this.shape=shape;
        this.parameterTypes=shape==3 ? null : Objects.requireNonNull(parameterTypes,"expectedParamTypes").clone();
        bridge=new Bridge(this,source,identity,creationContext,names,functions);
    }

    /** Part 0 is literal/delegate/base, part 1 is the property, part 2 is inline arguments. */
    protected abstract Object evaluatePart(ELContext context,int part);

    @Override public final Object invoke(ELContext context,Object[] parameters) {
        Objects.requireNonNull(context,"context");
        return invokeChecked(context,parameters,List.of());
    }
    private Object invokeChecked(ELContext context,Object[] parameters,List<Class<?>> inheritedReturns) {
        context.notifyBeforeEvaluation(source);
        var returns=new ArrayList<>(inheritedReturns);
        if (expectedReturn!=null) returns.add(expectedReturn);
        Object value;
        if (shape==0) {
            if (expectedReturn==void.class) throw new ELException("Literal method expression cannot return void");
            Class<?> type=expectedReturn==null ? String.class : expectedReturn;
            returns.forEach(expected->checkReturn(expected,type));
            value=evaluatePart(context,0);
            if (expectedReturn!=null) value=context.convertToType(value,expectedReturn);
        } else if (shape==1) {
            var delegate=delegate(context);
            if (delegate instanceof CompiledMethodExpression compiled) value=compiled.invokeChecked(context,parameters,returns);
            else {
                var info=delegate.getMethodInfo(context);
                returns.forEach(expected->checkReturn(expected,info.getReturnType()));
                value=delegate.invoke(context,parameters);
            }
        } else {
            var resolved=resolve(context,parameters);
            if (!returns.isEmpty()) {
                var info=metadata(context,resolved).getMethodInfo();
                returns.forEach(expected->checkReturn(expected,info.getReturnType()));
            }
            context.setPropertyResolved(false);
            value=guarded(()->context.getELResolver().invoke(context,resolved.base(),resolved.name(),resolved.types(),resolved.arguments()));
            if (!context.isPropertyResolved() && shape==2) {
                requireReflection(resolved.base());
                ELResolver fallback=resolved.base() instanceof ELClass ? new StaticFieldELResolver() : new BeanELResolver();
                value=guarded(()->fallback.invoke(context,resolved.base(),resolved.name(),resolved.types(),resolved.arguments()));
            }
            if (!context.isPropertyResolved()) throw new MethodNotFoundException("Unresolved method "+resolved.name());
        }
        context.notifyAfterEvaluation(source);
        return value;
    }
    @Override public final MethodInfo getMethodInfo(ELContext context) {
        Objects.requireNonNull(context,"context");
        if (shape==0) return new MethodInfo(source,expectedReturn==null ? String.class : expectedReturn,parameterTypes.clone());
        var info=shape==1 ? delegate(context).getMethodInfo(context) : metadata(context,resolve(context,null)).getMethodInfo();
        checkReturn(expectedReturn,info.getReturnType());
        return info;
    }
    @Override public final MethodReference getMethodReference(ELContext context) {
        Objects.requireNonNull(context,"context");
        context.notifyBeforeEvaluation(source);
        MethodReference reference=shape==0 ? null : shape==1 ? delegate(context).getMethodReference(context) : metadata(context,resolve(context,null));
        if (reference!=null) checkReturn(expectedReturn,reference.getMethodInfo().getReturnType());
        context.notifyAfterEvaluation(source);
        return reference;
    }
    private MethodExpression delegate(ELContext context) {
        Object value=evaluatePart(context,0);
        if (value instanceof MethodExpression method) return method;
        throw new MethodNotFoundException("Variable does not resolve to a MethodExpression");
    }
    private Resolved resolve(ELContext context,Object[] parameters) {
        Object base=evaluatePart(context,0);
        if (base==null) throw new PropertyNotFoundException("Unresolved property null base");
        Object property=evaluatePart(context,1);
        if (property==null) throw new PropertyNotFoundException("Unresolved property null property");
        String name=context.convertToType(property,String.class);
        Object[] arguments=shape==3 ? (Object[])evaluatePart(context,2) : parameters==null ? new Object[0] : parameters.clone();
        return new Resolved(base,name,parameterTypes==null ? null : parameterTypes.clone(),arguments);
    }
    private record Resolved(Object base,String name,Class<?>[] types,Object[] arguments) {}
    private static MethodReference metadata(ELContext context,Resolved resolved) {
        Class<?> type=resolved.base() instanceof ELClass owner ? owner.getKlass() : resolved.base().getClass();
        var table=CompiledFunctionValueExpression.registered(context,type);
        MethodReference generated=table==null ? null : guarded(()->table.getMethodReference(context,resolved.base(),resolved.name(),resolved.types(),resolved.arguments()));
        if (generated!=null) return generated;
        requireReflection(resolved.base());
        var candidates=new LinkedHashMap<List<Class<?>>,Method>();
        collect(type,resolved.name(),resolved.base() instanceof ELClass,candidates,new HashSet<>());
        var methods=new ArrayList<>(candidates.values());
        var descriptors=new MethodDescriptor[methods.size()];
        for (int i=0;i<methods.size();i++) {
            var method=methods.get(i);
            descriptors[i]=new MethodDescriptor(method.getName(),method.getReturnType(),method.getParameterTypes(),
                    method.isVarArgs(),Modifier.isStatic(method.getModifiers()),new java.lang.annotation.Annotation[0]);
        }
        int selected=MethodSelection.select(context,descriptors,resolved.name(),resolved.types(),resolved.arguments(),resolved.base() instanceof ELClass);
        var method=methods.get(selected);
        return new MethodReference(resolved.base(),new MethodInfo(method.getName(),method.getReturnType(),method.getParameterTypes()),
                method.getAnnotations(),resolved.arguments());
    }
    private static void collect(Class<?> type,String name,boolean staticOnly,Map<List<Class<?>>,Method> candidates,Set<Class<?>> visited) {
        if (type==null || !visited.add(type)) return;
        for (var method:type.getMethods()) {
            Class<?> owner=method.getDeclaringClass();
            Module api=ExpressionFactory.class.getModule();
            if (!method.getName().equals(name) || !Modifier.isPublic(method.getModifiers())
                    || staticOnly && !Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(owner.getModifiers())
                    || owner.getModule()!=api && !owner.getModule().isExported(owner.getPackageName(),api)) continue;
            var key=Arrays.asList(method.getParameterTypes());
            var previous=candidates.get(key);
            if (previous==null || previous.isBridge() && !method.isBridge()
                    || previous.getReturnType()!=method.getReturnType() && previous.getReturnType().isAssignableFrom(method.getReturnType()))
                candidates.put(key,method);
        }
        for (Class<?> contract:type.getInterfaces()) collect(contract,name,staticOnly,candidates,visited);
        collect(type.getSuperclass(),name,staticOnly,candidates,visited);
    }
    private static void requireReflection(Object base) {
        switch (System.getProperty("peano.reflection","allow")) {
            case "allow" -> {}
            case "deny" -> throw new ELException("Reflection fallback denied for "+(base instanceof ELClass owner ? owner.getKlass() : base.getClass()).getName());
            default -> throw new ELException("Invalid peano.reflection value; expected allow or deny");
        }
    }
    private static void checkReturn(Class<?> expected,Class<?> actual) {
        if (expected!=null && !boxed(expected).isAssignableFrom(boxed(actual)))
            throw new MethodNotFoundException("Method return type does not match "+expected.getTypeName());
    }
    private static Class<?> boxed(Class<?> type) {
        if (type==boolean.class) return Boolean.class;
        if (type==char.class) return Character.class;
        if (type==byte.class) return Byte.class;
        if (type==short.class) return Short.class;
        if (type==int.class) return Integer.class;
        if (type==long.class) return Long.class;
        if (type==float.class) return Float.class;
        if (type==double.class) return Double.class;
        return type;
    }
    private static <T> T guarded(java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (ELException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ELException("Resolver operation failed",failure); }
    }
    @Override public final boolean isParametersProvided() { return shape==3; }
    @Override public final boolean isLiteralText() { return shape==0; }
    @Override public final String getExpressionString() { return source; }
    @Override public final boolean equals(Object other) {
        return other instanceof CompiledMethodExpression method && expectedReturn==method.expectedReturn
                && Arrays.equals(parameterTypes,method.parameterTypes) && bridge.equals(method.bridge);
    }
    @Override public final int hashCode() { return Objects.hash(expectedReturn,Arrays.hashCode(parameterTypes),bridge); }

    protected final Object readIdentifier(ELContext context,String name) { return bridge.readIdentifier(context,name); }
    protected final Object readProperty(ELContext context,Object base,Object key) { return bridge.readProperty(context,base,key); }
    protected final Object invokeProperty(ELContext context,Object base,Object key,Object[] arguments) { return bridge.invokeProperty(context,base,key,arguments); }
    protected final Object resolveNamedCall(ELContext context,String name) { return bridge.resolveNamedCall(context,name); }
    protected final Object invokeResolvedCall(ELContext context,Object target,Object[] arguments) { return bridge.invokeResolvedCall(context,target,arguments); }
    protected final Object invokeFunction(ELContext context,String key,Object[] arguments) { return bridge.invokeFunction(context,key,arguments); }
    protected final Consumer<Object> prepareRootWrite(ELContext context,String name) { return bridge.prepareRootWrite(context,name); }
    protected final Consumer<Object> preparePropertyWrite(ELContext context,Object base,Object key) { return bridge.preparePropertyWrite(context,base,key); }
    protected final LambdaExpression createLambda(ELContext context,int ordinal,String[] parameters,String[] names) {
        return bridge.createLambda(context,ordinal,parameters,names);
    }
    private static final class Bridge extends CompiledLambdaValueExpression {
        private static final long serialVersionUID=1L;
        private final CompiledMethodExpression owner;
        Bridge(CompiledMethodExpression owner,String source,String identity,ELContext context,String[] names,String[] functions) {
            super(source,identity,Object.class,context,names,false,functions); this.owner=owner;
        }
        @Override protected Object evaluate(ELContext context) { return owner.evaluatePart(context,0); }
        @Override protected Object evaluateLambda(ELContext context,int ordinal) { return owner.evaluatePart(context,ordinal+3); }
    }
    private void readObject(java.io.ObjectInputStream input) throws java.io.IOException,ClassNotFoundException {
        input.defaultReadObject();
        if (source==null || bridge==null || shape<0 || shape>3 || shape!=3 && parameterTypes==null)
            throw new java.io.InvalidObjectException("Invalid compiled method expression metadata");
    }
}
