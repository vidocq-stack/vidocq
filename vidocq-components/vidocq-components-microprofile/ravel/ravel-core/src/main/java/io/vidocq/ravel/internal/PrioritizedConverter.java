/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.Converter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Tuple {priorité, converter} — usage interne pour la résolution MP Config 3.1 §5.3.
 *
 * <p>Le converter avec la priorité <b>la plus haute</b> gagne ; en cas d'égalité,
 * le dernier enregistré écrase le précédent.</p>
 *
 * <p>La priorité par défaut applicative est <b>100</b> (spec §5.3) ; les built-in
 * sont à <b>1</b> ({@link BuiltInConverters#BUILT_IN_PRIORITY}).</p>
 */
record PrioritizedConverter(int priority, Converter<?> converter) {

    /** Priorité par défaut MP §5.3 si {@code @Priority} est absent. */
    static final int DEFAULT_PRIORITY = 100;

    /**
     * Lit la priorité d'un converter selon §5.3 :
     * <ul>
     *   <li>{@code @jakarta.annotation.Priority(value)} → cette valeur</li>
     *   <li>sinon → {@link #DEFAULT_PRIORITY}</li>
     * </ul>
     *
     * <p>Implémentation par réflexion sur le nom qualifié pour ne pas imposer
     * {@code jakarta.annotation} en dépendance compile de {@code ravel-core}.</p>
     */
    static int readPriority(Converter<?> converter) {
        for (Annotation a : converter.getClass().getAnnotations()) {
            String fqn = a.annotationType().getName();
            if ("jakarta.annotation.Priority".equals(fqn)
                    || "javax.annotation.Priority".equals(fqn)) {
                try {
                    Method valueMethod = a.annotationType().getMethod("value");
                    Object v = valueMethod.invoke(a);
                    if (v instanceof Integer i) {
                        return i;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // pas d'accesseur value() — annotation atypique, on retombe au défaut
                }
            }
        }
        return DEFAULT_PRIORITY;
    }
}

