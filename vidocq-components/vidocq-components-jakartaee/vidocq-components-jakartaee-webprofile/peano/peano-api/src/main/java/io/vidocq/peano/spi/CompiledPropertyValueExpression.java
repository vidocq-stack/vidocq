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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.MethodNotFoundException;
import jakarta.el.PropertyNotFoundException;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.ValueExpression;
import jakarta.el.ValueReference;

/** Resolver protocol for application-owned compiled property navigation. */
public abstract class CompiledPropertyValueExpression extends CompiledValueExpression {
    private static final long serialVersionUID=1L;
    private static final String CONTEXT="context";
    private final Map<String,ValueExpression> bindings;
    private final boolean composite;

    /** A null creationContext selects resolver lookup without variable capture. */
    protected CompiledPropertyValueExpression(String source,String identity,Class<?> expectedType,
            ELContext creationContext,String[] names) {
        this(source,identity,expectedType,creationContext,names,false);
    }

    /** Composite metadata is String without evaluating template parts. */
    protected CompiledPropertyValueExpression(String source,String identity,Class<?> expectedType,
            ELContext creationContext,String[] names,boolean composite) {
        super(source,identity,expectedType,false,composite ? String.class : null);
        this.composite=composite;
        var captured=new LinkedHashMap<String,ValueExpression>();
        var mapper=creationContext==null ? null : creationContext.getVariableMapper();
        if (mapper!=null) {
            for (String name:names) {
                if (captured.containsKey(name)) continue;
                ValueExpression expression=mapper.resolveVariable(name);
                if (expression!=null) captured.put(name,expression);
            }
        }
        bindings=Map.copyOf(captured);
    }

    /** Evaluates the final base and key without reading the final property getter. */
    protected ValueReference locate(ELContext context) { return null; }

    protected final Object readIdentifier(ELContext context,String name) {
        return CompiledRootValueExpression.resolveRoot(context,name,bindings.get(name));
    }

    protected final Object readCallable(ELContext context,String name) {
        if (context.isLambdaArgument(name)) return context.getLambdaArgument(name);
        ValueExpression binding=bindings.get(name);
        if (binding!=null) return binding.getValue(context);
        context.setPropertyResolved(false);
        return guarded(()->context.getELResolver().getValue(context,null,name));
    }

    protected final Object readProperty(ELContext context,Object base,Object key) {
        context.setPropertyResolved(false);
        Object value=guarded(()->context.getELResolver().getValue(context,base,key));
        if (!context.isPropertyResolved()) throw missing(key);
        return value;
    }

    protected final Object invokeProperty(ELContext context,Object base,Object key,Object[] arguments) {
        context.setPropertyResolved(false);
        Object value=guarded(()->context.getELResolver().invoke(context,base,key,null,arguments));
        if (!context.isPropertyResolved()) throw new MethodNotFoundException("Unresolved method "+key);
        return value;
    }

    protected final java.util.function.Consumer<Object> prepareRootWrite(ELContext context,String name) {
        if (context.isLambdaArgument(name)) throw new PropertyNotWritableException("Lambda arguments are read-only");
        ValueExpression binding=bindings.get(name);
        if (binding!=null) {
            var reference=binding.getValueReference(context);
            if (reference!=null) return new LocationWrite(context,new ValueReference(reference.getBase(),reference.getProperty()));
            if (binding.isReadOnly(context)) throw new PropertyNotWritableException("Variable is read-only");
            return new BindingWrite(context,binding);
        }
        return new LocationWrite(context,new ValueReference(null,name));
    }

    protected final java.util.function.Consumer<Object> preparePropertyWrite(ELContext context,Object base,Object key) {
        return new LocationWrite(context,new ValueReference(base,key));
    }

    private record LocationWrite(ELContext context,ValueReference location) implements java.util.function.Consumer<Object> {
        public void accept(Object value) { CompiledRootValueExpression.writeReference(context,location,value); }
    }

    private record BindingWrite(ELContext context,ValueExpression binding) implements java.util.function.Consumer<Object> {
        public void accept(Object value) { binding.setValue(context,value); }
    }

    @Override protected Object bindingIdentity() { return bindings; }

    @Override public final Class<?> getType(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        if (composite) return String.class;
        var location=locate(context);
        if (location==null) {
            Object value=evaluate(context);
            return value==null ? null : value.getClass();
        }
        context.setPropertyResolved(false);
        Class<?> type=guarded(()->context.getELResolver().getType(context,location.getBase(),location.getProperty()));
        if (!context.isPropertyResolved()) throw missing(location.getProperty());
        return type;
    }

    @Override public final boolean isReadOnly(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        var location=locate(context);
        if (location==null) return true;
        context.setPropertyResolved(false);
        boolean result=guarded(()->context.getELResolver().isReadOnly(context,location.getBase(),location.getProperty()));
        if (!context.isPropertyResolved()) throw missing(location.getProperty());
        return result;
    }

    @Override public final ValueReference getValueReference(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        return locate(context);
    }

    @Override public final void setValue(ELContext context,Object value) {
        Objects.requireNonNull(context,CONTEXT);
        var location=locate(context);
        if (location==null) throw new PropertyNotWritableException("This expression is read-only");
        context.setPropertyResolved(false);
        Class<?> target=guarded(()->context.getELResolver().getType(context,location.getBase(),location.getProperty()));
        Object converted=target!=null && context.isPropertyResolved() ? context.convertToType(value,target) : value;
        context.setPropertyResolved(false);
        guarded(()-> { context.getELResolver().setValue(context,location.getBase(),location.getProperty(),converted); return null; });
        if (!context.isPropertyResolved()) throw new PropertyNotWritableException("Unresolved property "+location.getProperty());
    }

    private static PropertyNotFoundException missing(Object key) {
        return new PropertyNotFoundException("Unresolved property "+key);
    }

    private static <T> T guarded(Supplier<T> operation) {
        try { return operation.get(); }
        catch (ELException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ELException("Resolver operation failed",failure); }
    }

    private void readObject(java.io.ObjectInputStream input)
            throws java.io.IOException,ClassNotFoundException {
        input.defaultReadObject();
        if (bindings==null) throw new java.io.InvalidObjectException("Invalid compiled property bindings");
    }
}

