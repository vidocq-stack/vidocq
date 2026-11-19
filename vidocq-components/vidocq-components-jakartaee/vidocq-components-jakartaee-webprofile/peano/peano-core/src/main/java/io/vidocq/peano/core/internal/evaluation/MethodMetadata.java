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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import jakarta.el.ELClass;
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.MethodNotFoundException;

/**
 * Documented metadata fallback for public methods accessible to the Jakarta API.
 * Invocation stays in ELResolver; this class never changes accessibility or invokes a member.
 */
public final class MethodMetadata {
    private MethodMetadata() {
    }

    private record Signature(String name, List<Class<?>> parameters) {
    }


    public static Method find(Object base, String name, Class<?>[] expectedTypes, Object[] values, ELContext context) {
        ReflectionGuard.requireAllowed(base);
        Class<?> type = base instanceof ELClass elClass ? elClass.getKlass() : base.getClass();
        boolean staticOnly = base instanceof ELClass;
        var candidates = new LinkedHashMap<Signature, Method>();
        collect(type, name, staticOnly, candidates, new HashSet<>());
        List<Method> methods = new ArrayList<>(candidates.values());
        var descriptors = new io.vidocq.peano.spi.MethodDescriptor[methods.size()];
        for (int i = 0; i < methods.size(); i++) {
            Method method = methods.get(i);
            descriptors[i] = new io.vidocq.peano.spi.MethodDescriptor(method.getName(), method.getReturnType(),
                    method.getParameterTypes(), method.isVarArgs(), Modifier.isStatic(method.getModifiers()),
                    new java.lang.annotation.Annotation[0]);
        }
        int selected = io.vidocq.peano.spi.MethodSelection.select(context, descriptors, name, expectedTypes, values, staticOnly);
        return methods.get(selected);
    }

    private static void collect(Class<?> type, String name, boolean staticOnly,
            Map<Signature, Method> candidates, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || !Modifier.isPublic(method.getModifiers())
                    || staticOnly && !Modifier.isStatic(method.getModifiers())
                    || !accessible(method.getDeclaringClass())) continue;
            Signature key = new Signature(name, Arrays.asList(method.getParameterTypes()));
            Method previous = candidates.get(key);
            if (previous == null || previous.isBridge() && !method.isBridge()
                    || previous.getReturnType() != method.getReturnType()
                    && previous.getReturnType().isAssignableFrom(method.getReturnType())) {
                candidates.put(key, method);
            }
        }
        for (Class<?> contract : type.getInterfaces()) collect(contract, name, staticOnly, candidates, visited);
        collect(type.getSuperclass(), name, staticOnly, candidates, visited);
    }

    private static boolean accessible(Class<?> type) {
        Module api = ExpressionFactory.class.getModule();
        return Modifier.isPublic(type.getModifiers())
                && (type.getModule() == api || type.getModule().isExported(type.getPackageName(), api));
    }


    public static void checkReturn(Class<?> expected, Class<?> actual) {
        if (expected != null && !boxed(expected).isAssignableFrom(boxed(actual))) {
            throw new MethodNotFoundException("Method return type does not match " + expected.getTypeName());
        }
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }
}

