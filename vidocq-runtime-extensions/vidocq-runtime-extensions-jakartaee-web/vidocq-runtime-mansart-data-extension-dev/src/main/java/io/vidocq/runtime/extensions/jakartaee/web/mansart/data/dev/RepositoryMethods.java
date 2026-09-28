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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.data.repository.Param;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The methods of a repository the panel offers (spec §1): every method the interface declares, neither static,
 * private nor synthetic, by name then signature, then the {@code findById}, {@code findAll}, {@code save},
 * {@code deleteById} and {@code delete} it inherits from Jakarta Data, same order. Their generic types are bound to
 * the repository's own, so that {@code BasicRepository.save(S)} takes a {@code Task} for a
 * {@code BasicRepository<Task, Long>}.
 */
final class RepositoryMethods {

    /** The Jakarta Data methods offered besides the declared ones. */
    static final Set<String> INHERITED = Set.of("findById", "findAll", "save", "deleteById", "delete");

    private static final String JAKARTA_DATA = "jakarta.data.repository";
    private static final int MAX_DEPTH = 16;

    /**
     * One method.
     *
     * @param method    the method, as the interface declares or inherits it
     * @param names     its parameters' names: {@code @Param}, else the real name, else {@code argN}
     * @param types     its parameters' classes, type variables bound
     * @param returns   its return type in simple names, type variables bound: {@code Optional<Task>}
     * @param inherited whether it comes from Jakarta Data
     */
    record Candidate(Method method, List<String> names, List<Class<?>> types, String returns, boolean inherited) {

        /** {@code name(Type, Type)}, in simple names: {@code search(String, int)}. */
        String signature() {
            StringJoiner out = new StringJoiner(", ", method.getName() + "(", ")");
            for (Class<?> type : types) {
                out.add(type.getSimpleName());
            }
            return out.toString();
        }
    }

    private RepositoryMethods() {}

    /** The methods of {@code repository}; reflection failures propagate, the caller lists the repository as such. */
    static List<Candidate> of(Class<?> repository) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        bind(repository, bindings, new HashSet<>());
        Comparator<Candidate> order = Comparator.comparing((Candidate c) -> c.method().getName())
                .thenComparing(Candidate::signature);

        List<Candidate> declared = new ArrayList<>();
        for (Method method : repository.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!method.isSynthetic() && !method.isBridge() && !Modifier.isStatic(modifiers)
                    && !Modifier.isPrivate(modifiers)) {
                declared.add(candidate(method, bindings, false));
            }
        }
        declared.sort(order);

        Map<String, Candidate> inherited = new HashMap<>();
        for (Method method : repository.getMethods()) {
            Class<?> owner = method.getDeclaringClass();
            if (owner != repository && JAKARTA_DATA.equals(owner.getPackageName())
                    && INHERITED.contains(method.getName()) && !Modifier.isStatic(method.getModifiers())) {
                inherited.putIfAbsent(method.getName() + Arrays.toString(method.getParameterTypes()),
                        candidate(method, bindings, true));
            }
        }
        List<Candidate> all = new ArrayList<>(declared);
        inherited.values().stream().sorted(order).forEach(all::add);
        return List.copyOf(all);
    }

    /** The name from {@code @Param}, else the real name when compiled with {@code -parameters}, else {@code argN}. */
    static String name(Parameter parameter, int index) {
        Param param = parameter.getAnnotation(Param.class);
        if (param != null && !param.value().isBlank()) {
            return param.value();
        }
        return parameter.isNamePresent() ? parameter.getName() : "arg" + index;
    }

    private static Candidate candidate(Method method, Map<TypeVariable<?>, Type> bindings, boolean inherited) {
        Parameter[] parameters = method.getParameters();
        Type[] generic;
        try {
            generic = method.getGenericParameterTypes();
        } catch (RuntimeException | LinkageError unreadable) {
            generic = method.getParameterTypes();
        }
        List<String> names = new ArrayList<>();
        List<Class<?>> types = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            names.add(name(parameters[i], i));
            types.add(resolve(i < generic.length ? generic[i] : parameters[i].getType(), bindings, 0));
        }
        String returns;
        try {
            returns = print(method.getGenericReturnType(), bindings, 0);
        } catch (RuntimeException | LinkageError unreadable) {
            returns = method.getReturnType().getSimpleName();
        }
        return new Candidate(method, List.copyOf(names), List.copyOf(types), returns, inherited);
    }

    /** Binds the type parameters of every super-interface of {@code type} to their arguments, however deep. */
    private static void bind(Class<?> type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> seen) {
        for (Type superType : type.getGenericInterfaces()) {
            if (superType instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> raw) {
                TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length && i < arguments.length; i++) {
                    bindings.putIfAbsent(variables[i], arguments[i]);
                }
                if (seen.add(raw)) {
                    bind(raw, bindings, seen);
                }
            } else if (superType instanceof Class<?> raw && seen.add(raw)) {
                bind(raw, bindings, seen);
            }
        }
    }

    /** The class {@code type} stands for: a variable's binding, else its first bound; {@code Object} at worst. */
    static Class<?> resolve(Type type, Map<TypeVariable<?>, Type> bindings, int depth) {
        if (depth > MAX_DEPTH) {
            return Object.class;
        }
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType array) {
            return resolve(array.getGenericComponentType(), bindings, depth + 1).arrayType();
        }
        if (type instanceof TypeVariable<?> variable) {
            Type bound = bindings.get(variable);
            if (bound != null) {
                return resolve(bound, bindings, depth + 1);
            }
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : resolve(bounds[0], bindings, depth + 1);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 ? Object.class : resolve(upper[0], bindings, depth + 1);
        }
        return Object.class;
    }

    /** {@code type} in simple names, variables bound: {@code Optional<Task>}. */
    static String print(Type type, Map<TypeVariable<?>, Type> bindings, int depth) {
        if (depth > MAX_DEPTH) {
            return "?";
        }
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                return print(c.getComponentType(), bindings, depth + 1) + "[]";
            }
            return c.getSimpleName().isEmpty() ? c.getName() : c.getSimpleName();
        }
        if (type instanceof ParameterizedType parameterized) {
            StringJoiner arguments = new StringJoiner(", ", "<", ">");
            for (Type argument : parameterized.getActualTypeArguments()) {
                arguments.add(print(argument, bindings, depth + 1));
            }
            return print(parameterized.getRawType(), bindings, depth + 1) + arguments;
        }
        if (type instanceof GenericArrayType array) {
            return print(array.getGenericComponentType(), bindings, depth + 1) + "[]";
        }
        if (type instanceof TypeVariable<?> variable) {
            Type bound = bindings.get(variable);
            if (bound != null) {
                return print(bound, bindings, depth + 1);
            }
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 || bounds[0] == Object.class
                    ? variable.getName() : print(bounds[0], bindings, depth + 1);
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return "? super " + print(wildcard.getLowerBounds()[0], bindings, depth + 1);
            }
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 || upper[0] == Object.class
                    ? "?" : "? extends " + print(upper[0], bindings, depth + 1);
        }
        return type.getTypeName();
    }
}
