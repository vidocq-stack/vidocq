package io.vidocq.mansart.data.dialect.attribute;

import io.vidocq.mansart.data.dialect.Attribute;

import java.lang.invoke.MethodHandle;

/**
 * M8-3 — wraps a leaf attribute that lives in a related entity, reachable through a chain of
 * {@code @ManyToOne}/{@code @OneToOne} relations. Used by JDQL path expressions like
 * {@code book.author.name}: parsed to {@code JoinedAttribute(leaf=Author.name, path=[Book.author])}.
 *
 * <p>Dialect-side: when rendering a {@code Where}/{@code OrderBy}, detect this subtype and
 * (a) emit the leaf's column qualified by the path's allocated alias, (b) add the path to the
 * set of joins to materialise in the {@code FROM} clause.
 *
 * <p>The wrapped getter/setter point to the leaf attribute on the leaf entity — the row mapper
 * doesn't materialise intermediate entities; only the leaf value is read from the result set.
 *
 * <p>{@code entityType()} returns the root entity (the one the query is anchored on) — not the
 * leaf entity — because consumers of the metamodel use it to validate "the attribute belongs
 * to this entity" assertions. {@code javaType()} returns the leaf's value type as expected.
 */
public record JoinedAttribute<E, V>(
        Attribute<?, V> leaf,
        JoinPath path,
        Class<E> entityType
) implements Attribute<E, V> {

    @Override public String name()         { return leaf.name(); }
    @Override public String columnName()   { return leaf.columnName(); }
    @Override public Class<V> javaType()   { return leaf.javaType(); }
    @Override public boolean nullable()    { return leaf.nullable(); }
    @Override public boolean unique()      { return leaf.unique(); }
    @Override public MethodHandle getter() { return leaf.getter(); }
    @Override public MethodHandle setter() { return leaf.setter(); }
}
