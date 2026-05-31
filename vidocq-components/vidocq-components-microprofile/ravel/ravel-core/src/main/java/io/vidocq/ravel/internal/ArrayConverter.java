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
 * Generic converter for arrays {@code T[]} (MP Config 3.1 §5.4), delegating each
 * element conversion to the component type converter after comma splitting.
 *
 * <p>Uses {@link Array#newInstance(Class, int)} and {@link Array#set} to support
 * both reference arrays ({@code Boolean[]}, {@code Duration[]}, ...) and
 * primitive arrays ({@code int[]}, {@code boolean[]}, ...).</p>
 *
 * @param <T> target type (array type, e.g. {@code int[]} or {@code Boolean[]}).
 */
final class ArrayConverter<T> implements Converter<T> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Class<?> componentType;
    private final Converter<?> elementConverter;

    ArrayConverter(Class<?> componentType, Converter<?> elementConverter) {
        this.componentType = Objects.requireNonNull(componentType, "componentType");
        this.elementConverter = Objects.requireNonNull(elementConverter, "elementConverter");
    }

    @Override
    @SuppressWarnings("unchecked")
    public T convert(String value) {
        if (value == null) return null;
        if (value.isEmpty()) return null; // §2.1.4 — empty string = missing
        List<String> parts = ArraySplitter.split(value);
        Object arr = Array.newInstance(componentType, parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Object element = elementConverter.convert(parts.get(i));
            Array.set(arr, i, element);
        }
        return (T) arr;
    }
}

