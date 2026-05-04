package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

public record TemporalAttribute<E, V>(
        String name,
        String columnName,
        Class<V> javaType,
        Class<E> entityType,
        boolean nullable,
        boolean unique,
        MethodHandle getter,
        MethodHandle setter
) implements Attribute<E, V> {
}
