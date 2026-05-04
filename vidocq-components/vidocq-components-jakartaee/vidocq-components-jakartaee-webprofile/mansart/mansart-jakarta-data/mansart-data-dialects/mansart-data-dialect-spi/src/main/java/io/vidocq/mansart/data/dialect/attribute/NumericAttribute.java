package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

public record NumericAttribute<E, V extends Number>(
        String name,
        String columnName,
        Class<V> javaType,
        Class<E> entityType,
        boolean nullable,
        boolean unique,
        int precision,
        int scale,
        MethodHandle getter,
        MethodHandle setter
) implements Attribute<E, V> {
}
