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
package io.vidocq.peano.core.internal;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.el.StandardELContext;
import jakarta.el.ValueExpression;

/**
 * Serialization proxy retaining source and captured bindings, without parser or context state.
 * Function descriptors restore public metadata only; invocation still uses the Jakarta resolver.
 */
record ExpressionSerialization(boolean methodExpression, boolean objectExpression,
        String source, Object instance, Class<?> expectedType, Class<?>[] parameterTypes,
        Map<String, ValueExpression> variables, Map<String, Function> functions) implements Serializable {
    private static final long serialVersionUID = 1L;

    private record Function(Class<?> declaringClass, String name, List<Class<?>> parameterTypes)
            implements Serializable {
        private static final long serialVersionUID = 1L;

        Method resolve() throws ReflectiveOperationException {
            io.vidocq.peano.core.internal.evaluation.ReflectionGuard.requireAllowedType(declaringClass);
            return declaringClass.getMethod(name, parameterTypes.toArray(Class<?>[]::new));
        }
    }

    ExpressionSerialization {
        parameterTypes = parameterTypes == null ? null : parameterTypes.clone();
        variables = Map.copyOf(variables);
        functions = Map.copyOf(functions);
    }

    static ExpressionSerialization create(boolean methodExpression, boolean objectExpression,
            String source, Object instance, Class<?> expectedType, Class<?>[] parameterTypes,
            Map<String, ValueExpression> variables, Map<String, Method> functions) {
        var descriptors = new HashMap<String, Function>();
        functions.forEach((key, method) -> descriptors.put(key, new Function(
                method.getDeclaringClass(), method.getName(), List.of(method.getParameterTypes()))));
        return new ExpressionSerialization(methodExpression, objectExpression, source, instance,
                expectedType, parameterTypes, variables, descriptors);
    }

    private Object readResolve() throws ObjectStreamException {
        if (objectExpression) return PeanoValueExpression.object(instance, expectedType);
        var context = new StandardELContext(new PeanoExpressionFactory());
        variables.forEach((name, value) -> context.getVariableMapper().setVariable(name, value));
        try {
            for (var entry : functions.entrySet()) {
                int separator = entry.getKey().indexOf(':');
                context.getFunctionMapper().mapFunction(entry.getKey().substring(0, separator),
                        entry.getKey().substring(separator + 1), entry.getValue().resolve());
            }
            return methodExpression
                    ? new PeanoMethodExpression(context, source, expectedType, parameterTypes)
                    : PeanoValueExpression.text(source, expectedType, context);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            var invalid = new InvalidObjectException("Cannot restore the serialized EL expression");
            invalid.initCause(exception);
            throw invalid;
        }
    }
}

