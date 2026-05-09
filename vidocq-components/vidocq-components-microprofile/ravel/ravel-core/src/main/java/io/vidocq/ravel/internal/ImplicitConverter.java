/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
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
 * <p>Algorithme officiel (dans cet ordre) :</p>
 * <ol>
 *   <li>une méthode {@code public static T of(String)}</li>
 *   <li>une méthode {@code public static T valueOf(String)}</li>
 *   <li>une méthode {@code public static T parse(CharSequence)}</li>
 *   <li>un constructeur {@code public T(String)}</li>
 *   <li>les types {@code Enum} bénéficient également de {@code valueOf(String)}
 *       (déjà capturé par l'étape 2).</li>
 * </ol>
 *
 * <p>Tout est <b>strictement public</b> ; pas de {@code setAccessible(true)} —
 * conforme aux contraintes d'architecture du projet.</p>
 */
final class ImplicitConverter {

    private ImplicitConverter() {
        // utilitaire
    }

    /**
     * Construit un converter pour le type cible si l'un des patterns §5.2 est détecté.
     *
     * @return le converter, ou {@code null} si aucun pattern n'est applicable.
     */
    static <T> Converter<T> create(Class<T> type) {
        if (type == null) return null;
        if (type.isPrimitive()) return null; // les primitives sont built-in
        if (type.isArray()) return null;     // arrays gérés ailleurs

        // 1. enum est traité explicitement : valueOf(String) existe en Java mais
        //    sa signature renvoie Enum<?>, plus pratique de passer par un converter dédié.
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

