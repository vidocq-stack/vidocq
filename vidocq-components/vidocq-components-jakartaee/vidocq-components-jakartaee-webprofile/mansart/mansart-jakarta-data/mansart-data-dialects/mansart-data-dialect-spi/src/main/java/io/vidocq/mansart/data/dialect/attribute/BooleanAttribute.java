package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

/**
 * Attribute backed by a {@code boolean}/{@link Boolean} field (cf. MANSART-001). Kept separate from
 * {@link NumericAttribute} because the latter's value type is bounded to {@link Number}, which
 * excludes {@code Boolean}; routing boolean fields here lets dialects map them to a native
 * {@code BOOLEAN} column via {@link #javaType()} without a {@code Number} type variable.
 */
public record BooleanAttribute<E>(
        String name,
        String columnName,
        Class<E> entityType,
        boolean nullable,
        boolean unique,
        MethodHandle getter,
        MethodHandle setter
) implements Attribute<E, Boolean> {

    @Override public Class<Boolean> javaType() { return Boolean.class; }
}
