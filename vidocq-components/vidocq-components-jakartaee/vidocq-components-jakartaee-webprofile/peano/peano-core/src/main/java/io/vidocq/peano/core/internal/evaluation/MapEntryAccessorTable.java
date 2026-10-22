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
import jakarta.el.MethodNotFoundException;
import jakarta.el.MethodReference;
import jakarta.el.PropertyNotWritableException;
import io.vidocq.peano.spi.AccessorTable;
import io.vidocq.peano.spi.MethodDescriptor;
import io.vidocq.peano.spi.MethodSelection;

/**
 * Built-in table for {@link Map.Entry}, mirroring a generated table for that interface.
 * JDK entries are private classes (HashMap$Node, the entries of Map.of and of unmodifiable
 * views), so no application table can name them; supertype lookup reaches this one.
 * key is read-only; value writes go through Map.Entry.setValue, and an entry that refuses
 * them (UnsupportedOperationException) yields PropertyNotWritableException.
 * The methods getKey(), getValue() and setValue(Object) are directly callable.
 */
final class MapEntryAccessorTable extends AccessorTable {
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final MethodDescriptor[] METHODS = {
        new MethodDescriptor("getKey", Object.class, new Class<?>[0], false, false, new java.lang.annotation.Annotation[0]),
        new MethodDescriptor("getValue", Object.class, new Class<?>[0], false, false, new java.lang.annotation.Annotation[0]),
        new MethodDescriptor("setValue", Object.class, new Class<?>[]{Object.class}, false, false,
                new java.lang.annotation.Annotation[0]),
    };

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

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        if (!accepts(context, base, property)) return null;
        return KEY.equals(property) ? null : Object.class;
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        return accepts(context, base, property) && KEY.equals(property);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (!accepts(context, base, property)) return;
        if (KEY.equals(property)) throw new PropertyNotWritableException("Read-only property key");
        Object converted = context.convertToType(value, Object.class);
        try {
            ((Map.Entry<Object, Object>) base).setValue(converted);
        } catch (UnsupportedOperationException refused) {
            throw new PropertyNotWritableException("Map entry value is not writable", refused);
        } catch (ELException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ELException("Map entry property operation failed", failure);
        }
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        Objects.requireNonNull(context);
        return base instanceof Map.Entry<?, ?> ? String.class : null;
    }

    private static boolean methodTarget(ELContext context, Object base, Object method) {
        Objects.requireNonNull(context);
        if (!(base instanceof Map.Entry<?, ?>) || !(method instanceof String name)) return false;
        for (var descriptor : METHODS) if (descriptor.name().equals(name)) return true;
        return false;
    }

    @Override
    public MethodReference getMethodReference(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        if (!methodTarget(context, base, method)) return null;
        var descriptor = METHODS[MethodSelection.select(context, METHODS, method, types, arguments, false)];
        return new MethodReference(base, descriptor.methodInfo(), descriptor.annotations(),
                arguments == null ? new Object[0] : arguments.clone());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
        if (!methodTarget(context, base, method)) return null;
        context.setPropertyResolved(base, method);
        Object[] args = arguments == null ? new Object[0] : arguments;
        int selected = MethodSelection.select(context, METHODS, (String) method, types, args, false);
        var entry = (Map.Entry<Object, Object>) base;
        try {
            return switch (selected) {
                case 0 -> { requireCount(args, 0); yield entry.getKey(); }
                case 1 -> { requireCount(args, 0); yield entry.getValue(); }
                case 2 -> { requireCount(args, 1); yield entry.setValue(context.convertToType(args[0], Object.class)); }
                default -> throw new MethodNotFoundException("Unknown method descriptor");
            };
        } catch (ELException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ELException("Map entry method invocation failed", failure);
        }
    }

    private static void requireCount(Object[] arguments, int expected) {
        if (arguments.length != expected) throw new MethodNotFoundException("Invalid argument count");
    }
}
