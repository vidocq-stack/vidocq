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
package io.vidocq.peano.core.internal.evaluation;

import java.util.Map;
import java.util.function.Supplier;
import java.lang.reflect.Method;
import jakarta.el.ELClass;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.ELException;
import jakarta.el.PropertyNotFoundException;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.MethodNotFoundException;
import jakarta.el.StaticFieldELResolver;
import jakarta.el.BeanELResolver;
import jakarta.el.ValueExpression;
import jakarta.el.ValueReference;
import io.vidocq.peano.core.internal.syntax.Ast;

/** Resolver operations with explicit resolution-flag handling and API-owned import access. */
public final class ResolverAccess {
    private ResolverAccess() {
    }

    public record Location(Object base, Object property) {
    }

    public static Object identifier(String name, ELContext context, Map<String, ValueExpression> variables) {
        requireContext(context);
        if (context.isLambdaArgument(name)) return context.getLambdaArgument(name);
        ValueExpression variable = variables.get(name);
        if (variable != null) return variable.getValue(context);
        context.setPropertyResolved(false);
        Object value = guarded(() -> context.getELResolver().getValue(context, null, name));
        if (context.isPropertyResolved()) return value;
        Location imported = importedField(context, name);
        if (imported != null) return read(context, imported);
        Class<?> type = context.getImportHandler().resolveClass(name);
        if (type != null) return new ELClass(type);
        throw missing(name);
    }

    public static Object read(ELContext context, Location location) {
        requireContext(context);
        context.setPropertyResolved(false);
        Object value = guarded(() -> context.getELResolver().getValue(context, location.base(), location.property()));
        if (!context.isPropertyResolved()) throw missing(location.property());
        return value;
    }

    public static Location property(Ast.Property property, ELContext context,
            Map<String, ValueExpression> variables) {
        return property(property, context, variables, Map.of());
    }

    public static Location property(Ast.Property property, ELContext context,
            Map<String, ValueExpression> variables, Map<String, Method> functions) {
        Object base = Interpreter.evaluate(property.base(), context, variables, functions);
        if (base == null) throw missing("null base");
        Object key = Interpreter.evaluate(property.key(), context, variables, functions);
        if (key == null) throw missing("null property");
        return new Location(base, key);
    }

    public static Class<?> type(ELContext context, Location location) {
        context.setPropertyResolved(false);
        Class<?> type = guarded(() -> context.getELResolver().getType(context, location.base(), location.property()));
        if (context.isPropertyResolved()) return type;
        if (location.base() == null && location.property() instanceof String name) {
            Location imported = importedField(context, name);
            if (imported != null) return type(context, imported);
            if (context.getImportHandler().resolveClass(name) != null) return ELClass.class;
        }
        throw missing(location.property());
    }

    public static boolean readOnly(ELContext context, Location location) {
        Location effective = effectiveRoot(context, location);
        if (effective == null) return true;
        context.setPropertyResolved(false);
        boolean readOnly = guarded(() -> context.getELResolver().isReadOnly(context, effective.base(), effective.property()));
        if (!context.isPropertyResolved()) throw missing(effective.property());
        return readOnly;
    }

    public static void write(ELContext context, Location location, Object value) {
        Location effective = effectiveRoot(context, location);
        if (effective == null) throw new PropertyNotWritableException("An imported class is read-only");
        Object converted = value;
        // Root names are dynamic namespace entries, not properties with a declared writable type.
        if (effective.base() != null) {
            context.setPropertyResolved(false);
            Class<?> type = guarded(() -> context.getELResolver().getType(context, effective.base(), effective.property()));
            if (type != null && context.isPropertyResolved()) converted = context.convertToType(value, type);
        }
        context.setPropertyResolved(false);
        Object assigned = converted;
        guarded(() -> {
            context.getELResolver().setValue(context, effective.base(), effective.property(), assigned);
            return null;
        });
        if (!context.isPropertyResolved()) throw new PropertyNotWritableException("Unresolved property " + effective.property());
    }

    public static Object invoke(ELContext context, Location location, Class<?>[] types, Object[] arguments) {
        return invoke(context, location, types, arguments, false);
    }

    /** Bare method references require direct API fallback after an unhandled custom chain (§1.6). */
    public static Object invokeBare(ELContext context, Location location, Class<?>[] types, Object[] arguments) {
        return invoke(context, location, types, arguments, true);
    }

    private static Object invoke(ELContext context, Location location, Class<?>[] types, Object[] arguments,
            boolean bareReference) {
        requireContext(context);
        context.setPropertyResolved(false);
        Object value = guarded(() -> context.getELResolver().invoke(
                context, location.base(), location.property(), types, arguments));
        if (!context.isPropertyResolved() && bareReference) {
            ELResolver fallback = location.base() instanceof ELClass ? new StaticFieldELResolver() : new BeanELResolver();
            value = guarded(() -> fallback.invoke(context, location.base(), location.property(), types, arguments));
        }
        if (!context.isPropertyResolved()) throw new MethodNotFoundException("Unresolved method " + location.property());
        return value;
    }

    public static Object function(ELContext context, Method method, Object[] arguments) {
        requireContext(context);
        context.setPropertyResolved(false);
        Object generated = guarded(() -> GeneratedAccessorELResolver.invokeRegistered(context,
                new ELClass(method.getDeclaringClass()), method.getName(), method.getParameterTypes(), arguments));
        if (context.isPropertyResolved()) return generated;
        Object value = guarded(() -> new StaticFieldELResolver().invoke(context,
                new ELClass(method.getDeclaringClass()), method.getName(), method.getParameterTypes(), arguments));
        if (!context.isPropertyResolved()) throw new MethodNotFoundException("Unresolved function " + method.getName());
        return value;
    }

    public static Object callable(String name, ELContext context, Map<String, ValueExpression> variables) {
        requireContext(context);
        if (context.isLambdaArgument(name)) return context.getLambdaArgument(name);
        ValueExpression variable = variables.get(name);
        if (variable != null) return variable.getValue(context);
        context.setPropertyResolved(false);
        return guarded(() -> context.getELResolver().getValue(context, null, name));
    }

    public static ValueReference reference(ELContext context, Location location) {
        Location effective = effectiveRoot(context, location);
        return effective == null ? null : new ValueReference(effective.base(), effective.property());
    }

    private static Location effectiveRoot(ELContext context, Location location) {
        if (location.base() != null || !(location.property() instanceof String name)) return location;
        context.setPropertyResolved(false);
        guarded(() -> context.getELResolver().isReadOnly(context, null, name));
        if (context.isPropertyResolved()) return location;
        Location imported = importedField(context, name);
        if (imported != null) return imported;
        if (context.getImportHandler().resolveClass(name) != null) return null;
        return location;
    }

    private static Location importedField(ELContext context, String name) {
        Class<?> type = context.getImportHandler().resolveStatic(name);
        return type == null ? null : new Location(new ELClass(type), name);
    }

    private static PropertyNotFoundException missing(Object property) {
        return new PropertyNotFoundException("Unresolved property " + property);
    }

    private static <T> T guarded(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ELException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ELException("Resolver operation failed", error);
        }
    }

    private static void requireContext(ELContext context) {
        if (context == null) throw new ELException("Resolver access requires an ELContext");
    }
}

