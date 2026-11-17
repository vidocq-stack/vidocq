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
package io.vidocq.mansart.data.cdi;

import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parameterized super-interfaces of a repository interface, as bean types of its synthetic bean (MANSART-068).
 *
 * <p>For {@code Authors extends CrudRepository<Author, Long>} they are {@code CrudRepository<Author, Long>} and
 * {@code BasicRepository<Author, Long>}: a type variable of a super-interface is replaced by the class the
 * repository gives it. The types are the real parameterized ones, never raw ones: a raw bean type does not satisfy
 * a parameterized injection point (CDI 4.1 §5.2.4).
 *
 * <p>A super-interface whose type argument is not a class once the variables are replaced (a wildcard, a nested
 * parameterized type, a variable the repository leaves open) is left out, and so are the interfaces above it.
 *
 * <p>The super-interfaces are read from the loaded interface, with {@code java.lang.reflect}: at the container start
 * the language model of the build compatible extension gives them without their type arguments
 * ({@code ClassInfo.superInterfaces()} answers {@code CrudRepository}, not {@code CrudRepository<Author, Long>}).
 */
final class RepositoryBeanTypes {

    private RepositoryBeanTypes() {
    }

    static List<Type> of(Types types, Class<?> repository) {
        Map<String, Type> found = new LinkedHashMap<>();
        collect(types, repository, Map.of(), found);
        return new ArrayList<>(found.values());
    }

    private static void collect(Types types, Class<?> type, Map<TypeVariable<?>, Class<?>> bound,
            Map<String, Type> found) {
        for (java.lang.reflect.Type parent : type.getGenericInterfaces()) {
            if (parent instanceof Class<?> plain) {
                collect(types, plain, Map.of(), found);
            } else if (parent instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> raw) {
                Class<?>[] arguments = resolve(parameterized.getActualTypeArguments(), bound);
                if (arguments.length > 0) {
                    found.putIfAbsent(parameterized(raw, arguments), types.parameterized(raw, arguments));
                    collect(types, raw, bind(raw, arguments), found);
                }
            }
        }
    }

    /** The classes of the arguments, or an empty array when one of them is not a class. */
    private static Class<?>[] resolve(java.lang.reflect.Type[] arguments, Map<TypeVariable<?>, Class<?>> bound) {
        Class<?>[] classes = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            Class<?> resolved = arguments[i] instanceof Class<?> c ? c : bound.get(arguments[i]);
            if (resolved == null) {
                return new Class<?>[0];
            }
            classes[i] = resolved;
        }
        return classes;
    }

    private static Map<TypeVariable<?>, Class<?>> bind(Class<?> raw, Class<?>[] arguments) {
        Map<TypeVariable<?>, Class<?>> bound = new HashMap<>();
        TypeVariable<?>[] variables = raw.getTypeParameters();
        for (int i = 0; i < variables.length; i++) {
            bound.put(variables[i], arguments[i]);
        }
        return bound;
    }

    private static String parameterized(Class<?> raw, Class<?>[] arguments) {
        StringBuilder key = new StringBuilder(raw.getName());
        for (Class<?> argument : arguments) {
            key.append(' ').append(argument.getName());
        }
        return key.toString();
    }
}
