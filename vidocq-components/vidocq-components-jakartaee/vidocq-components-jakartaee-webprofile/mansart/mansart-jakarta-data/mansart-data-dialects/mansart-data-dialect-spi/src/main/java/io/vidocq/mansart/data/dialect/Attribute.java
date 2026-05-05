package io.vidocq.mansart.data.dialect;

import java.lang.invoke.MethodHandle;

/**
 * Compile-time-resolved description of one entity attribute.
 *
 * <p>Subtypes (see {@code io.vidocq.mansart.data.dialect.attribute}) carry kind-specific information used
 * by the dialect to generate specialized SQL without runtime {@code instanceof} chains.
 *
 * <p>The {@link #getter()} and {@link #setter()} method handles are obtained once at the
 * {@code <clinit>} of the generated metamodel via {@code MethodHandles.privateLookupIn(...)}. They are
 * the only path used to read/write entity state — Mansart never falls back to {@code java.lang.reflect}.
 */
public sealed interface Attribute<E, V>
        permits io.vidocq.mansart.data.dialect.attribute.IdAttribute,
                io.vidocq.mansart.data.dialect.attribute.TextAttribute,
                io.vidocq.mansart.data.dialect.attribute.NumericAttribute,
                io.vidocq.mansart.data.dialect.attribute.TemporalAttribute,
                io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute,
                io.vidocq.mansart.data.dialect.attribute.EnumAttribute,
                io.vidocq.mansart.data.dialect.attribute.VersionAttribute,
                // M8-3 — wraps a leaf attribute reachable via a chain of @ManyToOne/@OneToOne
                // relations (e.g. book.author.name resolves to JoinedAttribute(leaf=Author.name,
                // path=[Book.author])). The dialect detects this subtype and emits aliased SQL
                // with the appropriate INNER JOIN clauses.
                io.vidocq.mansart.data.dialect.attribute.JoinedAttribute {

    String name();
    String columnName();
    Class<V> javaType();
    Class<E> entityType();
    boolean nullable();
    boolean unique();
    MethodHandle getter();
    MethodHandle setter();
}
