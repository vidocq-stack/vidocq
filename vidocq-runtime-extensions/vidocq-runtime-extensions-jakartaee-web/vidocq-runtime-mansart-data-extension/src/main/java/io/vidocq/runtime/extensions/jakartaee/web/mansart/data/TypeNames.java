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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.StringJoiner;

/** A type as a developer writes it, in simple names: {@code List<Task>}, {@code Optional<Task>}, {@code long}. */
final class TypeNames {

    private TypeNames() {}

    /** The type in simple names; its raw name when it cannot be printed, {@code ?} when even that fails. */
    static String of(Type type) {
        try {
            return print(type);
        } catch (RuntimeException | LinkageError unreadable) {
            return raw(type);
        }
    }

    private static String raw(Type type) {
        try {
            return type.getTypeName();
        } catch (RuntimeException | LinkageError unreadable) {
            return "?";
        }
    }

    private static String print(Type type) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                return print(c.getComponentType()) + "[]";
            }
            String simple = c.getSimpleName();
            return simple.isEmpty() ? c.getName() : simple;
        }
        if (type instanceof ParameterizedType parameterized) {
            StringJoiner arguments = new StringJoiner(", ", "<", ">");
            for (Type argument : parameterized.getActualTypeArguments()) {
                arguments.add(print(argument));
            }
            return print(parameterized.getRawType()) + arguments;
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return "? super " + print(wildcard.getLowerBounds()[0]);
            }
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 || upper[0] == Object.class ? "?" : "? extends " + print(upper[0]);
        }
        if (type instanceof GenericArrayType array) {
            return print(array.getGenericComponentType()) + "[]";
        }
        if (type instanceof TypeVariable<?> variable) {
            return variable.getName();
        }
        return raw(type);
    }
}
