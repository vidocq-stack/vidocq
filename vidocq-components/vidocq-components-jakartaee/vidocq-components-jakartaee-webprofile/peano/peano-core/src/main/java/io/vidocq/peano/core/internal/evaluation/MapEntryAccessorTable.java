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
import java.util.Objects;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.MethodReference;
import jakarta.el.PropertyNotWritableException;
import io.vidocq.peano.spi.AccessorTable;
import io.vidocq.peano.spi.MethodDescriptor;

/**
 * Built-in table for {@link Map.Entry}, aligned with the API BeanELResolver, the reflective oracle.
 * JDK entries are private classes (HashMap$Node, the entries of Map.of and of unmodifiable
 * views), so no application table can name them; supertype lookup reaches this one.
 * key and value are read-only properties: Map.Entry.setValue returns V, so it is not a JavaBeans
 * setter, the rule peano-processor applies too. getKey(), getValue() and setValue(Object) are
 * callable as methods. A call of another shape (an application overload such as getValue(String))
 * and any other property stay unresolved for the next table or resolver.
 */
final class MapEntryAccessorTable extends AccessorTable {
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final java.lang.annotation.Annotation[] NO_ANNOTATIONS = new java.lang.annotation.Annotation[0];
    private static final MethodDescriptor GET_KEY = new MethodDescriptor("getKey", Object.class, new Class<?>[0],
            false, false, NO_ANNOTATIONS);
    private static final MethodDescriptor GET_VALUE = new MethodDescriptor("getValue", Object.class, new Class<?>[0],
            false, false, NO_ANNOTATIONS);
    private static final MethodDescriptor SET_VALUE = new MethodDescriptor("setValue", Object.class,
            new Class<?>[]{Object.class}, false, false, NO_ANNOTATIONS);

    @Override public Class<?> targetType() { return Map.Entry.class; }

    private static boolean accepts(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (base instanceof Map.Entry<?, ?> && (KEY.equals(property) || VALUE.equals(property))) {
            context.setPropertyResolved(base, property);
            return true;
        }
        return false;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        if (!accepts(context, base, property)) return null;
        var entry = (Map.Entry<?, ?>) base;
        return KEY.equals(property) ? entry.getKey() : entry.getValue();
    }

    /** Read-only properties report no settable type. */
    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        accepts(context, base, property);
        return null;
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        return accepts(context, base, property);
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (accepts(context, base, property)) throw new PropertyNotWritableException("Read-only property " + property);
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        Objects.requireNonNull(context);
        return base instanceof Map.Entry<?, ?> ? String.class : null;
    }

    /**
     * The descriptor whose name and arity match the call, or null: the arity comes from the
     * declared parameter types when given, otherwise from the arguments, and both must agree.
     */
    private static MethodDescriptor shape(ELContext context, Object base, Object method, Class<?>[] types,
            Object[] arguments) {
        Objects.requireNonNull(context);
        if (!(base instanceof Map.Entry<?, ?>) || !(method instanceof String name)) return null;
        MethodDescriptor descriptor = switch (name) {
            case "getKey" -> GET_KEY;
            case "getValue" -> GET_VALUE;
            case "setValue" -> SET_VALUE;
            default -> null;
        };
        if (descriptor == null) return null;
        int arity = descriptor.parameterTypes().length;
        if (types != null && (types.length != arity || arity == 1 && types[0] != Object.class)) return null;
        if (arguments != null && arguments.length != arity) return null;
        if (types == null && arguments == null && arity != 0) return null;
        return descriptor;
    }

    @Override
    public MethodReference getMethodReference(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        MethodDescriptor descriptor = shape(context, base, method, types, arguments);
        if (descriptor == null) return null;
        return new MethodReference(base, descriptor.methodInfo(), descriptor.annotations(),
                arguments == null ? new Object[0] : arguments.clone());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
        MethodDescriptor descriptor = shape(context, base, method, types, arguments == null ? new Object[0] : arguments);
        if (descriptor == null) return null;
        context.setPropertyResolved(base, method);
        var entry = (Map.Entry<Object, Object>) base;
        try {
            if (descriptor == GET_KEY) return entry.getKey();
            if (descriptor == GET_VALUE) return entry.getValue();
            return entry.setValue(context.convertToType(arguments[0], Object.class));
        } catch (ELException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ELException("Map entry method invocation failed", failure);
        }
    }
}
