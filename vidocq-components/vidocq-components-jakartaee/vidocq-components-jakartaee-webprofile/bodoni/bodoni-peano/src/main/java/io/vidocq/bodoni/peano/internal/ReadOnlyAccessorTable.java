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
package io.vidocq.bodoni.peano.internal;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.MethodReference;
import jakarta.el.PropertyNotWritableException;
import io.vidocq.peano.spi.AccessorTable;
import io.vidocq.peano.spi.MethodDescriptor;
import io.vidocq.peano.spi.MethodSelection;

/**
 * Base of the accessor tables of a type whose properties are all read-only and each
 * backed by one no-argument getter. Property {@code i} is read by method {@code i}; a subclass
 * supplies the two parallel lists and the switch that calls the getter.
 */
abstract class ReadOnlyAccessorTable extends AccessorTable {
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    private final Class<?> target;
    private final List<String> properties;
    private final MethodDescriptor[] methods;

    /**
     * @param target the type this table serves
     * @param properties the property names, in the order of {@code methods}
     * @param methods the no-argument getters, one per property
     */
    ReadOnlyAccessorTable(Class<?> target, List<String> properties, MethodDescriptor[] methods) {
        this.target = target;
        this.properties = List.copyOf(properties);
        this.methods = methods.clone();
    }

    /** Describes a public no-argument getter. */
    static MethodDescriptor descriptor(String name, Class<?> returnType) {
        return new MethodDescriptor(name, returnType, NO_TYPES, false, false, NO_ANNOTATIONS);
    }

    /** Calls the getter of property {@code index} on {@code base}, an instance of the target. */
    protected abstract Object read(Object base, int index);

    /** Name of the target in the message of a failed invocation. */
    protected String subject() {
        return target.getSimpleName();
    }

    @Override
    public final Class<?> targetType() {
        return target;
    }

    private int propertyIndex(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (target.isInstance(base) && property instanceof String name) {
            int index = properties.indexOf(name);
            if (index >= 0) {
                context.setPropertyResolved(base, property);
            }
            return index;
        }
        return -1;
    }

    @Override
    public final Object getValue(ELContext context, Object base, Object property) {
        int index = propertyIndex(context, base, property);
        return index < 0 ? null : read(base, index);
    }

    @Override
    public final Class<?> getType(ELContext context, Object base, Object property) {
        propertyIndex(context, base, property);
        return null;
    }

    @Override
    public final boolean isReadOnly(ELContext context, Object base, Object property) {
        return propertyIndex(context, base, property) >= 0;
    }

    @Override
    public final void setValue(ELContext context, Object base, Object property, Object value) {
        if (propertyIndex(context, base, property) >= 0) {
            throw new PropertyNotWritableException("Read-only property " + property);
        }
    }

    @Override
    public final Class<?> getCommonPropertyType(ELContext context, Object base) {
        Objects.requireNonNull(context);
        return target.isInstance(base) ? String.class : null;
    }

    /** Claims a call only when the name is one of the getters and no argument is passed. */
    private boolean methodTarget(ELContext context, Object base, Object method, Object[] arguments) {
        Objects.requireNonNull(context);
        if (!target.isInstance(base) || !(method instanceof String name)
                || (arguments != null && arguments.length != 0)) {
            return false;
        }
        for (var descriptor : methods) {
            if (descriptor.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public final MethodReference getMethodReference(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        if (!methodTarget(context, base, method, arguments)) {
            return null;
        }
        var descriptor = methods[MethodSelection.select(context, methods, method, types, arguments, false)];
        return new MethodReference(base, descriptor.methodInfo(), descriptor.annotations(),
                arguments == null ? new Object[0] : arguments.clone());
    }

    @Override
    public final Object invoke(ELContext context, Object base, Object method, Class<?>[] types,
            Object[] arguments) {
        if (!methodTarget(context, base, method, arguments)) {
            return null;
        }
        context.setPropertyResolved(base, method);
        Object[] args = arguments == null ? new Object[0] : arguments;
        int selected = MethodSelection.select(context, methods, (String) method, types, args, false);
        try {
            return read(base, selected);
        } catch (ELException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ELException(subject() + " method invocation failed", failure);
        }
    }
}
