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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.DataRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads one {@code @Repository} interface by reflection, once per boot: its primary entity and id type, its declared
 * methods, and the methods it inherits from Jakarta Data. Best effort: what reflection cannot read is left out, never
 * thrown — a method it cannot read is kept as {@code other}, with no parameters.
 */
final class RepositoryReader {

    /** The Jakarta Data interfaces whose type arguments name a repository's primary entity and its id. */
    private static final Set<Class<?>> PRIMARY =
            Set.of(BasicRepository.class, CrudRepository.class, DataRepository.class);

    private static final String JAKARTA_DATA = "jakarta.data.repository";

    /**
     * What a repository interface says of itself.
     *
     * @param entity   its primary entity, {@code null} when it has none
     * @param idType   the simple name of its id type, {@code null} when it has no primary entity
     * @param methods  all its declared methods, in name order
     * @param inherits {@code inherits BasicRepository: delete, …}, or empty
     */
    record Read(Class<?> entity, String idType, List<MansartDataCatalogue.Method> methods, String inherits) {}

    private RepositoryReader() {}

    /** Reads {@code repository}, cutting a query after {@code maxQuery} characters. */
    static Read read(Class<?> repository, int maxQuery) {
        Type[] primary = primaryTypes(repository);
        Class<?> entity = primary == null ? null : rawClass(primary[0]);
        String idType = entity == null ? null : TypeNames.of(primary[1]);
        return new Read(entity, idType, methods(repository, maxQuery), inherits(repository));
    }

    /**
     * The type arguments of {@code BasicRepository}, {@code CrudRepository} or {@code DataRepository}, however deep in
     * the super-interfaces, with the type variables of the interfaces in between bound; {@code null} when there is
     * none or it cannot be read.
     */
    static Type[] primaryTypes(Class<?> repository) {
        try {
            return primaryTypes(repository, Map.of());
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private static Type[] primaryTypes(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
        for (Type superType : type.getGenericInterfaces()) {
            Class<?> raw;
            Type[] arguments;
            if (superType instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> rawType) {
                raw = rawType;
                arguments = parameterized.getActualTypeArguments().clone();
                for (int i = 0; i < arguments.length; i++) {
                    Type bound = bindings.get(arguments[i]);
                    if (bound != null) {
                        arguments[i] = bound;
                    }
                }
            } else if (superType instanceof Class<?> rawType) {
                raw = rawType;
                arguments = new Type[0];
            } else {
                continue;
            }
            if (PRIMARY.contains(raw) && arguments.length == 2) {
                return arguments;
            }
            Map<TypeVariable<?>, Type> next = new HashMap<>();
            TypeVariable<?>[] parameters = raw.getTypeParameters();
            for (int i = 0; i < parameters.length && i < arguments.length; i++) {
                next.put(parameters[i], arguments[i]);
            }
            Type[] found = primaryTypes(raw, next);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The class a type argument names, or {@code null} for a type variable or a wildcard. */
    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    /** The interface's own methods, neither static, private nor synthetic, by name then parameters. */
    static List<MansartDataCatalogue.Method> methods(Class<?> repository, int maxQuery) {
        Method[] declared;
        try {
            declared = repository.getDeclaredMethods();
        } catch (RuntimeException | LinkageError unreadable) {
            return List.of();
        }
        List<MansartDataCatalogue.Method> methods = new ArrayList<>();
        for (Method method : declared) {
            int modifiers = method.getModifiers();
            if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(modifiers)
                    || Modifier.isPrivate(modifiers)) {
                continue;
            }
            methods.add(method(method, maxQuery));
        }
        methods.sort(Comparator.comparing(MansartDataCatalogue.Method::name)
                .thenComparing(MansartDataCatalogue.Method::parameters));
        return methods;
    }

    private static MansartDataCatalogue.Method method(Method method, int maxQuery) {
        try {
            Query query = method.getAnnotation(Query.class);
            return new MansartDataCatalogue.Method(method.getName(), kind(method, query),
                    query == null ? "" : cut(query.value(), maxQuery), parameters(method), returns(method));
        } catch (RuntimeException | LinkageError unreadable) {
            return new MansartDataCatalogue.Method(method.getName(), "other", "", "", "");
        }
    }

    private static String kind(Method method, Query query) {
        if (query != null) {
            return "JDQL";
        }
        if (method.isAnnotationPresent(Find.class)) {
            return "@Find";
        }
        if (method.isAnnotationPresent(Insert.class)) {
            return "@Insert";
        }
        if (method.isAnnotationPresent(Update.class)) {
            return "@Update";
        }
        if (method.isAnnotationPresent(Delete.class)) {
            return "@Delete";
        }
        if (method.isAnnotationPresent(Save.class)) {
            return "@Save";
        }
        return derived(method.getName()) ? "derived" : "other";
    }

    /** A query derived from the method name: it starts with find, count, exists or delete, and holds By. */
    static boolean derived(String name) {
        return (name.startsWith("find") || name.startsWith("count") || name.startsWith("exists")
                || name.startsWith("delete")) && name.contains("By");
    }

    private static String parameters(Method method) {
        Parameter[] parameters = method.getParameters();
        Type[] types = parameterTypes(method);
        StringJoiner out = new StringJoiner(", ");
        for (int i = 0; i < parameters.length; i++) {
            Param param = parameters[i].getAnnotation(Param.class);
            String name = parameterName(param == null ? null : param.value(), parameters[i].isNamePresent(),
                    parameters[i].getName(), i);
            out.add(name + ": " + TypeNames.of(i < types.length ? types[i] : parameters[i].getType()));
        }
        return out.toString();
    }

    /** The name from {@code @Param}, else the real name when compiled with {@code -parameters}, else {@code argN}. */
    static String parameterName(String param, boolean namePresent, String realName, int index) {
        if (param != null && !param.isBlank()) {
            return param;
        }
        return namePresent ? realName : "arg" + index;
    }

    private static Type[] parameterTypes(Method method) {
        try {
            return method.getGenericParameterTypes();
        } catch (RuntimeException | LinkageError unreadable) {
            return method.getParameterTypes();
        }
    }

    private static String returns(Method method) {
        try {
            return TypeNames.of(method.getGenericReturnType());
        } catch (RuntimeException | LinkageError unreadable) {
            return TypeNames.of(method.getReturnType());
        }
    }

    /**
     * {@code inherits BasicRepository: delete, …}: the first {@code jakarta.data.repository} interfaces reached, and
     * the names of their methods, each once, in name order; empty when there is none, or none with a method.
     */
    static String inherits(Class<?> repository) {
        try {
            Map<String, Class<?>> reached = new TreeMap<>();
            collectJakartaData(repository, reached, new HashSet<>());
            Set<String> names = new TreeSet<>();
            for (Class<?> type : reached.values()) {
                for (Method method : type.getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        names.add(method.getName());
                    }
                }
            }
            if (names.isEmpty()) {
                return "";
            }
            return "inherits " + String.join(", ", reached.keySet()) + ": " + String.join(", ", names);
        } catch (RuntimeException | LinkageError unreadable) {
            return "";
        }
    }

    private static void collectJakartaData(Class<?> type, Map<String, Class<?>> reached, Set<Class<?>> seen) {
        for (Class<?> superType : type.getInterfaces()) {
            if (!seen.add(superType)) {
                continue;
            }
            if (JAKARTA_DATA.equals(superType.getPackageName())) {
                reached.put(superType.getSimpleName(), superType);
            } else {
                collectJakartaData(superType, reached, seen);
            }
        }
    }

    /** {@code text} cut after {@code max} characters, with {@code …}. */
    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
