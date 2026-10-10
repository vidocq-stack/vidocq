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
import jakarta.el.ELException;
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

    private record Score(int exact, int assignable, int coercible, boolean varargs) implements Comparable<Score> {
        @Override
        public int compareTo(Score other) {
            int order = Integer.compare(exact, other.exact);
            if (order == 0) order = Integer.compare(assignable, other.assignable);
            if (order == 0) order = Integer.compare(coercible, other.coercible);
            if (order == 0) order = Boolean.compare(other.varargs, varargs);
            return order;
        }
    }

    public static Method find(Object base, String name, Class<?>[] expectedTypes, Object[] values, ELContext context) {
        Class<?> type = base instanceof ELClass elClass ? elClass.getKlass() : base.getClass();
        boolean staticOnly = base instanceof ELClass;
        var candidates = new LinkedHashMap<Signature, Method>();
        collect(type, name, staticOnly, candidates, new HashSet<>());
        Class<?>[] actual = expectedTypes;
        if (actual == null) {
            actual = new Class<?>[values == null ? 0 : values.length];
            for (int i = 0; i < actual.length; i++) actual[i] = values[i] == null ? null : values[i].getClass();
        }
        var best = new ArrayList<Method>();
        Score bestScore = null;
        for (Method method : candidates.values()) {
            Score score = score(method, actual, values, context);
            if (score == null) continue;
            int order = bestScore == null ? 1 : score.compareTo(bestScore);
            if (order > 0) {
                best.clear();
                best.add(method);
                bestScore = score;
            } else if (order == 0) {
                best.add(method);
            }
        }
        for (Method candidate : best) {
            if (best.stream().allMatch(other -> candidate == other || moreSpecific(candidate, other))) return candidate;
        }
        throw new MethodNotFoundException("No unique accessible method " + name);
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

    private static Score score(Method method, Class<?>[] actual, Object[] values, ELContext context) {
        Class<?>[] formal = method.getParameterTypes();
        if (!method.isVarArgs() && actual.length != formal.length
                || method.isVarArgs() && actual.length < formal.length - 1) return null;
        int exact = 0;
        int assignable = 0;
        int coercible = 0;
        for (int i = 0; i < actual.length; i++) {
            Class<?> target;
            if (method.isVarArgs() && i >= formal.length - 1) {
                boolean packed = i == formal.length - 1 && actual.length == formal.length
                        && actual[i] != null && formal[i].isAssignableFrom(actual[i]);
                target = packed ? formal[i] : formal[formal.length - 1].getComponentType();
            } else {
                target = formal[i];
            }
            Class<?> source = actual[i];
            if (source == null) {
                coercible++;
            } else if (source == target) {
                exact++;
            } else if (boxed(target).isAssignableFrom(boxed(source))) {
                assignable++;
            } else if (canCoerce(source, target, values != null && i < values.length ? values[i] : null,
                    values != null && i < values.length, context)) {
                coercible++;
            } else {
                return null;
            }
        }
        return new Score(exact, assignable, coercible, method.isVarArgs());
    }

    private static boolean canCoerce(Class<?> source, Class<?> target, Object value, boolean hasValue, ELContext context) {
        if (hasValue) {
            try {
                context.convertToType(value, target);
                return true;
            } catch (ELException error) {
                return false;
            }
        }
        Class<?> from = boxed(source);
        Class<?> to = boxed(target);
        return to == String.class || from == String.class && (Number.class.isAssignableFrom(to)
                || to == Boolean.class || to == Character.class || to.isEnum())
                || Number.class.isAssignableFrom(from) && (Number.class.isAssignableFrom(to) || to == Character.class)
                || from == Character.class && Number.class.isAssignableFrom(to);
    }

    private static boolean moreSpecific(Method a, Method b) {
        if (a == null || b == null) return false;
        Class<?>[] left = a.getParameterTypes();
        Class<?>[] right = b.getParameterTypes();
        if (left.length != right.length) return false;
        boolean narrower = false;
        for (int i = 0; i < left.length; i++) {
            if (!boxed(right[i]).isAssignableFrom(boxed(left[i]))) return false;
            narrower |= boxed(left[i]) != boxed(right[i]);
        }
        return narrower;
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

