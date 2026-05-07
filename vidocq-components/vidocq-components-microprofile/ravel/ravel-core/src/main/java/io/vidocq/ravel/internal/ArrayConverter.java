/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.Converter;

import java.io.Serial;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Objects;

/**
 * Converter générique pour {@code T[]} (MP Config 3.1 §5.4) — délègue la conversion
 * de chaque élément au converter du type composant après split par virgule.
 *
 * @param <T> type composant
 */
final class ArrayConverter<T> implements Converter<T[]> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Class<T> componentType;
    private final Converter<T> elementConverter;

    ArrayConverter(Class<T> componentType, Converter<T> elementConverter) {
        this.componentType = Objects.requireNonNull(componentType, "componentType");
        this.elementConverter = Objects.requireNonNull(elementConverter, "elementConverter");
    }

    @Override
    @SuppressWarnings("unchecked")
    public T[] convert(String value) {
        if (value == null) return null;
        if (value.isEmpty()) return null; // §2.1.4 — empty string = missing
        List<String> parts = ArraySplitter.split(value);
        T[] arr = (T[]) Array.newInstance(componentType, parts.size());
        for (int i = 0; i < parts.size(); i++) {
            arr[i] = elementConverter.convert(parts.get(i));
        }
        return arr;
    }
}

