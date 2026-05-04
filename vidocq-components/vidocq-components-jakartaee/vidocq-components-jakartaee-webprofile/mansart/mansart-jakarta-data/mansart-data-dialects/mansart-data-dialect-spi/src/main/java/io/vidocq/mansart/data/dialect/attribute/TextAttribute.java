package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

public record TextAttribute<E>(
        String name,
        String columnName,
        Class<E> entityType,
        boolean nullable,
        boolean unique,
        int length,
        MethodHandle getter,
        MethodHandle setter
) implements Attribute<E, String> {

    @Override public Class<String> javaType() { return String.class; }
}
