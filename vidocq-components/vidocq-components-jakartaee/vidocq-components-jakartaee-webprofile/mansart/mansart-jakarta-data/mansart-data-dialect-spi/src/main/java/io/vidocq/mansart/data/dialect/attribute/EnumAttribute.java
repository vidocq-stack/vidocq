package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.EnumType;
import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

public record EnumAttribute<E, V extends Enum<V>>(
        String name,
        String columnName,
        Class<V> javaType,
        Class<E> entityType,
        boolean nullable,
        boolean unique,
        EnumType storage,
        MethodHandle getter,
        MethodHandle setter
) implements Attribute<E, V> {
}
