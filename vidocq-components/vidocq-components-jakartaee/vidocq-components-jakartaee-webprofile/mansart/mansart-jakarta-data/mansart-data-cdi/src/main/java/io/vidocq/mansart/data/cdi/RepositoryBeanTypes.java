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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parameterized super-interfaces of a repository interface, as bean types of its synthetic bean (MANSART-068).
 *
 * <p>For {@code Authors extends CrudRepository<Author, Long>} they are {@code CrudRepository<Author, Long>},
 * {@code BasicRepository<Author, Long>} and {@code DataRepository<Author, Long>}: a type variable of a
 * super-interface is replaced by the class the repository gives it. The types are the real parameterized ones, never
 * raw ones: a raw bean type does not satisfy a parameterized injection point (CDI 4.1 §5.2.4).
 *
 * <p>A super-interface whose type argument is not a class once the variables are replaced (a wildcard, a nested
 * parameterized type, a variable the repository leaves open) is left out, and so are the interfaces above it.
 *
 * <p>The interfaces are read from the language model of the build compatible extension, not by reflection, so the
 * walk is the same at build, at the container start and on another CDI container. A type variable is matched by its
 * name and position: two {@code TypeVariable} objects of one variable are not always equal (Vauban BUG-20261117-33).
 * The model must give super-interfaces as declared, also for a class of a dependency jar such as
 * {@code CrudRepository} (Vauban BUG-20261117-22 and BUG-20261117-31); on an older Vauban the walk finds nothing and
 * the bean keeps the repository interface as its only type.
 */
final class RepositoryBeanTypes {

    private RepositoryBeanTypes() {
    }

    static List<Type> of(Types types, Class<?> repository) {
        Map<String, Type> found = new LinkedHashMap<>();
        collect(types, types.ofClass(repository.getName()).declaration(), Map.of(), found);
        return new ArrayList<>(found.values());
    }

    private static void collect(Types types, ClassInfo type, Map<String, ClassType> bound, Map<String, Type> found) {
        for (Type parent : type.superInterfaces()) {
            if (parent.isClass()) {
                collect(types, parent.asClass().declaration(), Map.of(), found);
            } else if (parent.isParameterizedType()) {
                ParameterizedType parameterized = parent.asParameterizedType();
                ClassType[] arguments = resolve(parameterized.typeArguments(), bound);
                if (arguments.length > 0) {
                    ClassType generic = parameterized.genericClass();
                    found.putIfAbsent(key(generic, arguments), types.parameterized(generic, arguments));
                    collect(types, generic.declaration(), bind(generic.declaration(), arguments), found);
                }
            }
        }
    }

    private static ClassType[] resolve(List<Type> arguments, Map<String, ClassType> bound) {
        ClassType[] classes = new ClassType[arguments.size()];
        for (int i = 0; i < classes.length; i++) {
            Type argument = arguments.get(i);
            ClassType resolved = null;
            if (argument.isClass()) {
                resolved = argument.asClass();
            } else if (argument.isTypeVariable()) {
                resolved = bound.get(argument.asTypeVariable().name());
            }
            if (resolved == null) {
                return new ClassType[0];
            }
            classes[i] = resolved;
        }
        return classes;
    }

    private static Map<String, ClassType> bind(ClassInfo generic, ClassType[] arguments) {
        Map<String, ClassType> bound = new HashMap<>();
        List<TypeVariable> variables = generic.typeParameters();
        for (int i = 0; i < variables.size() && i < arguments.length; i++) {
            bound.put(variables.get(i).name(), arguments[i]);
        }
        return bound;
    }

    private static String key(ClassType generic, ClassType[] arguments) {
        StringBuilder key = new StringBuilder(generic.declaration().name());
        for (ClassType argument : arguments) {
            key.append(' ').append(argument.declaration().name());
        }
        return key.toString();
    }
}
