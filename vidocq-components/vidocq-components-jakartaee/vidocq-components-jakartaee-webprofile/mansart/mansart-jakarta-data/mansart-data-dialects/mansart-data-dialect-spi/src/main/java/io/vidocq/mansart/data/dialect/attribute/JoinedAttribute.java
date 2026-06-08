/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
