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

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Supplier;
import jakarta.el.*;

/** Creation-time FunctionMapper descriptors for build-time compiled expressions. */
public abstract class CompiledFunctionValueExpression extends CompiledPropertyValueExpression {
    private static final long serialVersionUID=1L;
    private final Map<String,FunctionBinding> functions;

    protected CompiledFunctionValueExpression(String source,String identity,Class<?> expectedType,
            ELContext creationContext,String[] names,boolean composite,String[] functionKeys) {
        super(source,identity,expectedType,creationContext,names,composite);
        var mapper=creationContext==null ? null : creationContext.getFunctionMapper();
        var captured=new LinkedHashMap<String,FunctionBinding>();
        for (String key:functionKeys) {
            int separator=key.indexOf(':');
            if (separator<0) throw new ELException("Invalid compiled function key "+key);
            String prefix=key.substring(0,separator),name=key.substring(separator+1);
            var method=mapper==null ? null : mapper.resolveFunction(prefix,name);
            if (method==null) {
                if (!prefix.isEmpty()) throw new ELException("Unmapped function "+key);
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                    || !Modifier.isPublic(method.getDeclaringClass().getModifiers()))
                throw new ELException("Function must reference a public static method: "+key);
            captured.put(key,new FunctionBinding(method.getDeclaringClass(),method.getName(),
                    Arrays.asList(method.getParameterTypes()),method.getReturnType()));
        }
        functions=Map.copyOf(captured);
    }

    @Override protected Object bindingIdentity() {
        return new BindingIdentity(super.bindingIdentity(),functions);
    }

    protected final Object resolveNamedCall(ELContext context,String name) {
        Object value=readCallable(context,name);
        if (value instanceof LambdaExpression) return value;
        String key=":"+name;
        if (functions.containsKey(key)) return new MappedCall(key);
        Class<?> type=context.getImportHandler().resolveClass(name);
        if (type!=null) return new ImportedCall(type,"<init>");
        type=context.getImportHandler().resolveStatic(name);
        if (type!=null) return new ImportedCall(type,name);
        throw new MethodNotFoundException("Unresolved function "+name);
    }

    protected final Object invokeResolvedCall(ELContext context,Object target,Object[] arguments) {
        if (target instanceof LambdaExpression lambda) return lambda.invoke(context,arguments);
        if (target instanceof MappedCall mapped) return invokeFunction(context,mapped.key(),arguments);
        var imported=(ImportedCall)target;
        return invokeProperty(context,new ELClass(imported.owner()),imported.name(),arguments);
    }

    private record MappedCall(String key) {}
    private record ImportedCall(Class<?> owner,String name) {}

    protected final Object invokeFunction(ELContext context,String key,Object[] arguments) {
        FunctionBinding function=functions.get(key);
        if (function==null) throw new MethodNotFoundException("Unmapped function "+key);
        var base=new ELClass(function.owner());
        Class<?>[] types=function.parameters().toArray(new Class<?>[0]);
        context.setPropertyResolved(false);
        Object value=guarded(()-> {
            AccessorTable table=registered(context,function.owner());
            return table==null ? null : table.invoke(context,base,function.name(),types,arguments);
        });
        if (context.isPropertyResolved()) return value;
        switch (System.getProperty("peano.reflection","allow")) {
            case "allow" -> {}
            case "deny" -> throw new ELException("Reflection fallback denied for "+function.owner().getName()
                    +"; register a generated accessor provider or a custom resolver");
            default -> throw new ELException("Invalid peano.reflection value; expected allow or deny");
        }
        Class<?>[] fallbackTypes=function.parameters().toArray(new Class<?>[0]);
        value=guarded(()->new StaticFieldELResolver().invoke(context,base,function.name(),fallbackTypes,arguments));
        if (!context.isPropertyResolved()) throw new MethodNotFoundException("Unresolved function "+function.name());
        return value;
    }

    private static AccessorTable registered(ELContext context,Class<?> owner) {
        ClassLoader loader=Thread.currentThread().getContextClassLoader();
        Object cached=context.getContext(Registry.class);
        if (cached instanceof Registry registry && registry.loader()==loader) return registry.tables().get(owner);
        var tables=new HashMap<Class<?>,AccessorTable>();
        try {
            for (var provider:ServiceLoader.load(AccessorProvider.class,loader)) {
                var accessors=provider.accessors();
                if (accessors==null) throw new ELException("Accessor provider returned null tables");
                for (var table:accessors) {
                    Class<?> target=table==null ? null : table.targetType();
                    if (target==null) throw new ELException("Accessor provider returned an invalid table");
                    if (tables.putIfAbsent(target,table)!=null)
                        throw new ELException("Duplicate accessor tables for "+target.getName());
                }
            }
        } catch (ServiceConfigurationError failure) {
            throw new ELException("Cannot load accessor providers",failure);
        }
        var registry=new Registry(loader,Map.copyOf(tables));
        context.putContext(Registry.class,registry);
        return registry.tables().get(owner);
    }

    private record Registry(ClassLoader loader,Map<Class<?>,AccessorTable> tables) {}

    private record FunctionBinding(Class<?> owner,String name,List<Class<?>> parameters,Class<?> returnType) implements Serializable {
        private static final long serialVersionUID=1L;
        FunctionBinding {
            Objects.requireNonNull(owner,"owner"); Objects.requireNonNull(name,"name");
            parameters=List.copyOf(parameters); Objects.requireNonNull(returnType,"returnType");
        }
    }
    private record BindingIdentity(Object variables,Object functions) {}

    private static <T> T guarded(Supplier<T> operation) {
        try { return operation.get(); }
        catch (ELException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ELException("Resolver operation failed",failure); }
    }

    private void readObject(java.io.ObjectInputStream input)
            throws java.io.IOException,ClassNotFoundException {
        input.defaultReadObject();
        if (functions==null) throw new java.io.InvalidObjectException("Invalid compiled function bindings");
    }
}

