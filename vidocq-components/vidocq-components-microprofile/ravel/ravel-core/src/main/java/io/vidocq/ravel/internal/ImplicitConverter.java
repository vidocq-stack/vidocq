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
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.Converter;

import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * MP Config 3.1 §5.2 — implicit (automatic) converter.
 *
 * <p>Official algorithm (in this order):</p>
 * <ol>
 *   <li>a {@code public static T of(String)} method</li>
 *   <li>a {@code public static T valueOf(String)} method</li>
 *   <li>a {@code public static T parse(CharSequence)} method</li>
 *   <li>a {@code public T(String)} constructor</li>
 *   <li>{@code Enum} types also benefit from {@code valueOf(String)}
 *       (already covered by step 2).</li>
 * </ol>
 *
 * <p>Everything is <b>strictly public</b>; no {@code setAccessible(true)} —
 * consistent with the project's architecture constraints.</p>
 */
final class ImplicitConverter {

    private ImplicitConverter() {
        // utility class
    }

    /**
     * Builds a converter for the target type if one of the §5.2 patterns is detected.
     *
     * @return the converter, or {@code null} if no pattern is applicable.
     */
    static <T> Converter<T> create(Class<T> type) {
        if (type == null) return null;
        if (type.isPrimitive()) return null; // primitives are built-in
        if (type.isArray()) return null;     // arrays handled elsewhere

        // 1. Enums are handled explicitly: valueOf(String) exists in Java but
        //    its signature returns Enum<?>, so a dedicated converter is more practical.
        if (type.isEnum()) {
            return enumConverter(type);
        }

        // 2. of(String)
        Method m = findStaticFactory(type, "of", String.class);
        if (m != null) return new MethodConverter<>(type, m);

        // 3. valueOf(String)
        m = findStaticFactory(type, "valueOf", String.class);
        if (m != null) return new MethodConverter<>(type, m);

        // 4. parse(CharSequence)
        m = findStaticFactory(type, "parse", CharSequence.class);
        if (m != null) return new MethodConverter<>(type, m);

        // 5. constructor (String)
        try {
            Constructor<T> ctor = type.getConstructor(String.class);
            return new ConstructorConverter<>(ctor);
        } catch (NoSuchMethodException ignored) {
            // fall through
        }

        return null;
    }

    private static Method findStaticFactory(Class<?> type, String name, Class<?> paramType) {
        try {
            Method m = type.getMethod(name, paramType);
            int mods = m.getModifiers();
            if (!Modifier.isStatic(mods)) return null;
            if (!type.isAssignableFrom(m.getReturnType())) return null;
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> Converter<T> enumConverter(Class<T> type) {
        return new EnumConverter(type);
    }

    // ---- backing converters ----

    private static final class MethodConverter<T> implements Converter<T> {
        @Serial private static final long serialVersionUID = 1L;
        private final Class<T> type;
        private final transient Method method;

        MethodConverter(Class<T> type, Method method) {
            this.type = type;
            this.method = method;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T convert(String value) {
            if (value == null) throw new NullPointerException("value");
            if (value.isEmpty()) return null;
            try {
                return (T) method.invoke(null, value);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException(
                        "Cannot invoke " + method + " on '" + value + "'", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalArgumentException iae) throw iae;
                if (cause instanceof NullPointerException npe) throw npe;
                throw new IllegalArgumentException(
                        "Cannot convert '" + value + "' to " + type.getName(), cause);
            }
        }
    }

    private static final class ConstructorConverter<T> implements Converter<T> {
        @Serial private static final long serialVersionUID = 1L;
        private final transient Constructor<T> ctor;

        ConstructorConverter(Constructor<T> ctor) {
            this.ctor = ctor;
        }

        @Override
        public T convert(String value) {
            if (value == null) throw new NullPointerException("value");
            if (value.isEmpty()) return null;
            try {
                return ctor.newInstance(value);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
                if (cause instanceof IllegalArgumentException iae) throw iae;
                if (cause instanceof NullPointerException npe) throw npe;
                throw new IllegalArgumentException(
                        "Cannot convert '" + value + "' to " + ctor.getDeclaringClass().getName(), cause);
            }
        }
    }

    private static final class EnumConverter<E extends Enum<E>> implements Converter<E> {
        @Serial private static final long serialVersionUID = 1L;
        private final Class<E> type;

        EnumConverter(Class<E> type) {
            this.type = type;
        }

        @Override
        public E convert(String value) {
            if (value == null) throw new NullPointerException("value");
            if (value.isEmpty()) return null;
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Cannot convert '" + value + "' to enum " + type.getName(), e);
            }
        }
    }
}

