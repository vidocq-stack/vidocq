package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

/**
 * Foreign-key reference to another entity ({@code @ManyToOne}/{@code @OneToOne}).
 *
 * <p>{@code columnName} is the FK column on the owning entity's table (e.g. {@code author_id}).
 * {@code javaType} is the target entity class. {@code referencedColumn} is the column on the
 * target entity's table that the FK points at — defaults to {@code "id"}, but may be overridden
 * via {@code @JoinColumn(referencedColumnName = ...)}.
 *
 * <p>M8-3 — added {@code referencedColumn} so {@link JoinPath} can render the JOIN condition
 * accurately ({@code parent.fk = child.referencedColumn}).
 */
public record ReferenceAttribute<E, V>(
        String name,
        String columnName,
        Class<V> javaType,
        Class<E> entityType,
        boolean nullable,
        boolean unique,
        boolean lazy,
        String referencedColumn,
        MethodHandle getter,
        MethodHandle setter
) implements Attribute<E, V> {

    /** Backwards-compatible constructor — defaults {@code referencedColumn} to {@code "id"}. */
    public ReferenceAttribute(String name, String columnName, Class<V> javaType, Class<E> entityType,
                              boolean nullable, boolean unique, boolean lazy,
                              MethodHandle getter, MethodHandle setter) {
        this(name, columnName, javaType, entityType, nullable, unique, lazy, "id", getter, setter);
    }
}
