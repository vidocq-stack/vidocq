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
            FunctionBinding binding=bind(mapper,key);
            if (binding!=null) captured.put(key,binding);
        }
        functions=Map.copyOf(captured);
    }

    /** The creation-time binding of one function key, or null for an unmapped default-namespace call. */
    private static FunctionBinding bind(FunctionMapper mapper,String key) {
        int separator=key.indexOf(':');
        if (separator<0) throw new ELException("Invalid compiled function key "+key);
        String prefix=key.substring(0,separator);
        String name=key.substring(separator+1);
        if (mapper instanceof CompiledFunctionMapper compiled) return compiledBinding(compiled,prefix,name,key);
        var method=mapper==null ? null : mapper.resolveFunction(prefix,name);
        if (method==null) return unmapped(prefix,key);
        if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                || !Modifier.isPublic(method.getDeclaringClass().getModifiers()))
            throw new ELException("Function must reference a public static method: "+key);
        return new FunctionBinding(method.getDeclaringClass(),method.getName(),
                Arrays.asList(method.getParameterTypes()),method.getReturnType(),null);
    }

    private static FunctionBinding compiledBinding(CompiledFunctionMapper compiled,String prefix,String name,String key) {
        var descriptor=compiled.resolveCompiledFunction(prefix,name);
        if (descriptor==null) return unmapped(prefix,key);
        var method=descriptor.method();
        return new FunctionBinding(descriptor.owner(),method.name(),
                Arrays.asList(method.parameterTypes()),method.returnType(),descriptor.invoker());
    }

    private static FunctionBinding unmapped(String prefix,String key) {
        if (!prefix.isEmpty()) throw new ELException("Unmapped function "+key);
        return null;
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
        if (target instanceof MappedCall(String key)) return invokeFunction(context,key,arguments);
        var imported=(ImportedCall)target;
        return invokeProperty(context,new ELClass(imported.owner()),imported.name(),arguments);
    }

    private record MappedCall(String key) {}
    private record ImportedCall(Class<?> owner,String name) {}

    protected final Object invokeFunction(ELContext context,String key,Object[] arguments) {
        FunctionBinding function=functions.get(key);
        if (function==null) throw new MethodNotFoundException("Unmapped function "+key);
        if (function.invoker()!=null) {
            try { return function.invoker().invoke(context,arguments); }
            catch (ELException failure) { throw failure; }
            catch (Throwable failure) { throw new ELException("Function invocation failed: "+key,failure); }
        }
        var base=new ELClass(function.owner());
        Class<?>[] types=function.parameters().toArray(new Class<?>[0]);
        context.setPropertyResolved(false);
        Object value=guarded(()-> {
            AccessorTable table=registered(context,getClass(),function.owner());
            return table==null ? null : table.invoke(context,base,function.name(),types,arguments);
        });
        if (context.isPropertyResolved()) return value;
        switch (System.getProperty("peano.reflection","allow")) {
            case "allow" -> {
                // Fall through to the reflective StaticFieldELResolver below.
            }
            case "deny" -> throw new ELException("Reflection fallback denied for "+function.owner().getName()
                    +"; register a generated accessor provider or a custom resolver");
            default -> throw new ELException("Invalid peano.reflection value; expected allow or deny");
        }
        Class<?>[] fallbackTypes=function.parameters().toArray(new Class<?>[0]);
        value=guarded(()->new StaticFieldELResolver().invoke(context,base,function.name(),fallbackTypes,arguments));
        if (!context.isPropertyResolved()) throw new MethodNotFoundException("Unresolved function "+function.name());
        return value;
    }

    /**
     * The accessor table registered for a type, from the providers visible to the thread context
     * class loader. When that loader is the compiled expression class's own, the snapshot taken
     * from it is kept per expression class, so each new context reuses it (EL-019); any other
     * context class loader gets a snapshot cached in the context, as before. The snapshot hangs
     * off the application's own generated class, never off a shared JDK class.
     */
    static AccessorTable registered(ELContext context,Class<?> expression,Class<?> owner) {
        if (expression.getClassLoader()==Thread.currentThread().getContextClassLoader())
            return REGISTRIES.get(expression).tables().get(owner);
        return registered(context,owner);
    }

    protected static AccessorTable registered(ELContext context,Class<?> owner) {
        ClassLoader loader=Thread.currentThread().getContextClassLoader();
        Object cached=context.getContext(Registry.class);
        if (cached instanceof Registry(ClassLoader cachedLoader,Map<Class<?>,AccessorTable> tables) && cachedLoader==loader)
            return tables.get(owner);
        var registry=load(loader);
        context.putContext(Registry.class,registry);
        return registry.tables().get(owner);
    }

    private static final ClassValue<Registry> REGISTRIES=new ClassValue<>() {
        @Override protected Registry computeValue(Class<?> expression) {
            return load(expression.getClassLoader());
        }
    };

    private static Registry load(ClassLoader loader) {
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
        return new Registry(loader,Map.copyOf(tables));
    }

    private record Registry(ClassLoader loader,Map<Class<?>,AccessorTable> tables) {}

    private record FunctionBinding(Class<?> owner,String name,List<Class<?>> parameters,Class<?> returnType,
            CompiledFunctionInvoker invoker) implements Serializable {
        private static final long serialVersionUID=1L;
        FunctionBinding {
            Objects.requireNonNull(owner,"owner"); Objects.requireNonNull(name,"name");
            parameters=List.copyOf(parameters); Objects.requireNonNull(returnType,"returnType");
        }
        @Override public boolean equals(Object other) {
            return other instanceof FunctionBinding binding && owner==binding.owner && name.equals(binding.name)
                    && parameters.equals(binding.parameters) && returnType==binding.returnType;
        }
        @Override public int hashCode() { return Objects.hash(owner,name,parameters,returnType); }
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

