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

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import jakarta.el.ELClass;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.PropertyNotFoundException;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.ValueExpression;
import jakarta.el.ValueReference;

/** Runtime protocol for a build-time compiled standalone root identifier. */
public abstract class CompiledRootValueExpression extends CompiledValueExpression {
    private static final long serialVersionUID=1L;
    private static final String CONTEXT="context";
    private final String name;
    private final ValueExpression binding;

    /** A null creationContext selects resolver lookup without VariableMapper capture. */
    protected CompiledRootValueExpression(String source,String identity,Class<?> expectedType,
            String name,ELContext creationContext) {
        super(source,identity,expectedType,false,null);
        this.name=Objects.requireNonNull(name,"name");
        var mapper=creationContext==null ? null : creationContext.getVariableMapper();
        this.binding=mapper==null ? null : mapper.resolveVariable(name);
    }

    @Override protected final Object bindingIdentity() {
        return binding==null ? Map.of() : Map.of(name,binding);
    }

    @Override protected final Object evaluate(ELContext context) {
        return resolveRoot(context,name,binding);
    }

    static Object resolveRoot(ELContext context,String name,ValueExpression binding) {
        if (context.isLambdaArgument(name)) return context.getLambdaArgument(name);
        if (binding!=null) return binding.getValue(context);
        context.setPropertyResolved(false);
        Object value=guarded(()->context.getELResolver().getValue(context,null,name));
        if (context.isPropertyResolved()) return value;
        Class<?> owner=context.getImportHandler().resolveStatic(name);
        if (owner!=null) return read(context,new ELClass(owner),name);
        Class<?> type=context.getImportHandler().resolveClass(name);
        if (type!=null) return new ELClass(type);
        throw new PropertyNotFoundException("Unresolved property "+name);
    }

    @Override public final Class<?> getType(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        if (context.isLambdaArgument(name)) {
            Object value=context.getLambdaArgument(name);
            return value==null ? null : value.getClass();
        }
        if (binding!=null) return binding.getType(context);
        return type(context,null);
    }

    @Override public final boolean isReadOnly(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        if (context.isLambdaArgument(name)) return true;
        if (binding!=null) return binding.isReadOnly(context);
        var location=effectiveRoot(context);
        if (location==null) return true;
        context.setPropertyResolved(false);
        boolean result=guarded(()->context.getELResolver().isReadOnly(context,location.getBase(),name));
        if (!context.isPropertyResolved()) throw missing();
        return result;
    }

    @Override public final void setValue(ELContext context,Object value) {
        Objects.requireNonNull(context,CONTEXT);
        if (context.isLambdaArgument(name)) throw new PropertyNotWritableException("Lambda arguments are read-only");
        if (binding!=null) { binding.setValue(context,value); return; }
        writeReference(context,new ValueReference(null,name),value);
    }

    static void writeReference(ELContext context,ValueReference reference,Object value) {
        var location=effectiveRoot(context,reference);
        if (location==null) throw new PropertyNotWritableException("An imported class is read-only");
        Object converted=value;
        if (location.getBase()!=null) {
            context.setPropertyResolved(false);
            Class<?> target=guarded(()->context.getELResolver().getType(context,location.getBase(),location.getProperty()));
            if (target!=null && context.isPropertyResolved()) converted=context.convertToType(value,target);
        }
        context.setPropertyResolved(false);
        Object assigned=converted;
        guarded(()-> { context.getELResolver().setValue(context,location.getBase(),location.getProperty(),assigned); return null; });
        if (!context.isPropertyResolved()) throw new PropertyNotWritableException("Unresolved property "+location.getProperty());
    }

    @Override public final ValueReference getValueReference(ELContext context) {
        Objects.requireNonNull(context,CONTEXT);
        if (context.isLambdaArgument(name)) return null;
        if (binding!=null) return binding.getValueReference(context);
        return effectiveRoot(context);
    }

    private ValueReference effectiveRoot(ELContext context) {
        return effectiveRoot(context,new ValueReference(null,name));
    }

    private static ValueReference effectiveRoot(ELContext context,ValueReference reference) {
        if (reference.getBase()!=null || !(reference.getProperty() instanceof String name)) return reference;
        context.setPropertyResolved(false);
        guarded(()->context.getELResolver().isReadOnly(context,null,name));
        if (context.isPropertyResolved()) return reference;
        Class<?> owner=context.getImportHandler().resolveStatic(name);
        if (owner!=null) return new ValueReference(new ELClass(owner),name);
        if (context.getImportHandler().resolveClass(name)!=null) return null;
        return reference;
    }

    private static Object read(ELContext context,Object base,String name) {
        context.setPropertyResolved(false);
        Object value=guarded(()->context.getELResolver().getValue(context,base,name));
        if (!context.isPropertyResolved()) throw new PropertyNotFoundException("Unresolved property "+name);
        return value;
    }

    private Class<?> type(ELContext context,Object base) {
        context.setPropertyResolved(false);
        Class<?> result=guarded(()->context.getELResolver().getType(context,base,name));
        if (context.isPropertyResolved()) return result;
        if (base==null) {
            Class<?> owner=context.getImportHandler().resolveStatic(name);
            if (owner!=null) return type(context,new ELClass(owner));
            if (context.getImportHandler().resolveClass(name)!=null) return ELClass.class;
        }
        throw missing();
    }

    private PropertyNotFoundException missing() {
        return new PropertyNotFoundException("Unresolved property "+name);
    }

    private static <T> T guarded(Supplier<T> operation) {
        try { return operation.get(); }
        catch (ELException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ELException("Resolver operation failed",failure); }
    }

    private void readObject(java.io.ObjectInputStream input)
            throws java.io.IOException,ClassNotFoundException {
        input.defaultReadObject();
        if (name==null) throw new java.io.InvalidObjectException("Invalid compiled root metadata");
    }
}

