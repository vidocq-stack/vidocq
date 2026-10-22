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
import java.util.Objects;
import java.util.Set;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.MethodReference;
import jakarta.el.PropertyNotWritableException;
import jakarta.servlet.jsp.jstl.core.LoopTagStatus;
import io.vidocq.peano.spi.AccessorTable;
import io.vidocq.peano.spi.MethodDescriptor;
import io.vidocq.peano.spi.MethodSelection;

/**
 * Accessor table for {@link LoopTagStatus}, the {@code varStatus} object of Tags 3.0 section 6.1.
 * The JSTL API implements it with an anonymous class, which no table can name, so the table is
 * keyed on the interface and reached by Peano's supertype lookup. All properties are read-only:
 * current, index, count, first, last, begin, end and step. The matching getters
 * (getCurrent, getIndex, getCount, isFirst, isLast, getBegin, getEnd, getStep) are callable.
 */
final class LoopTagStatusAccessorTable extends AccessorTable {
    private static final Set<String> PROPERTIES =
            Set.of("current", "index", "count", "first", "last", "begin", "end", "step");
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];
    private static final MethodDescriptor[] METHODS = {
        descriptor("getCurrent", Object.class),
        descriptor("getIndex", int.class),
        descriptor("getCount", int.class),
        descriptor("isFirst", boolean.class),
        descriptor("isLast", boolean.class),
        descriptor("getBegin", Integer.class),
        descriptor("getEnd", Integer.class),
        descriptor("getStep", Integer.class),
    };

    private static MethodDescriptor descriptor(String name, Class<?> returnType) {
        return new MethodDescriptor(name, returnType, NO_TYPES, false, false, NO_ANNOTATIONS);
    }

    @Override
    public Class<?> targetType() {
        return LoopTagStatus.class;
    }

    private static boolean accepts(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (base instanceof LoopTagStatus && property instanceof String name && PROPERTIES.contains(name)) {
            context.setPropertyResolved(base, property);
            return true;
        }
        return false;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        if (!accepts(context, base, property)) {
            return null;
        }
        var status = (LoopTagStatus) base;
        return switch ((String) property) {
            case "current" -> status.getCurrent();
            case "index" -> status.getIndex();
            case "count" -> status.getCount();
            case "first" -> status.isFirst();
            case "last" -> status.isLast();
            case "begin" -> status.getBegin();
            case "end" -> status.getEnd();
            default -> status.getStep();
        };
    }

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
        if (accepts(context, base, property)) {
            throw new PropertyNotWritableException("Read-only property " + property);
        }
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        Objects.requireNonNull(context);
        return base instanceof LoopTagStatus ? String.class : null;
    }

    /** Claims a call only when the name is one of the eight methods and no argument is passed. */
    private static boolean methodTarget(ELContext context, Object base, Object method, Object[] arguments) {
        Objects.requireNonNull(context);
        if (!(base instanceof LoopTagStatus) || !(method instanceof String name)
                || (arguments != null && arguments.length != 0)) {
            return false;
        }
        for (var descriptor : METHODS) {
            if (descriptor.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MethodReference getMethodReference(ELContext context, Object base, String method,
            Class<?>[] types, Object[] arguments) {
        if (!methodTarget(context, base, method, arguments)) {
            return null;
        }
        var descriptor = METHODS[MethodSelection.select(context, METHODS, method, types, arguments, false)];
        return new MethodReference(base, descriptor.methodInfo(), descriptor.annotations(),
                arguments == null ? new Object[0] : arguments.clone());
    }

    @Override
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
        if (!methodTarget(context, base, method, arguments)) {
            return null;
        }
        context.setPropertyResolved(base, method);
        Object[] args = arguments == null ? new Object[0] : arguments;
        int selected = MethodSelection.select(context, METHODS, (String) method, types, args, false);
        var status = (LoopTagStatus) base;
        try {
            return switch (selected) {
                case 0 -> status.getCurrent();
                case 1 -> status.getIndex();
                case 2 -> status.getCount();
                case 3 -> status.isFirst();
                case 4 -> status.isLast();
                case 5 -> status.getBegin();
                case 6 -> status.getEnd();
                default -> status.getStep();
            };
        } catch (ELException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ELException("Loop status method invocation failed", failure);
        }
    }
}
