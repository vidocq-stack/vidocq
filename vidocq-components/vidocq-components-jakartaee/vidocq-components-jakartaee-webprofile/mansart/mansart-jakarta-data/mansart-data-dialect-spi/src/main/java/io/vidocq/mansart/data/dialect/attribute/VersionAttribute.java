package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

public record VersionAttribute<E, V extends Number>(
        String name,
        String columnName,
        Class<V> javaType,
        Class<E> entityType,
        MethodHandle getter,
        MethodHandle setter
) implements Attribute<E, V> {

    @Override public boolean nullable() { return false; }
    @Override public boolean unique()   { return false; }
}
