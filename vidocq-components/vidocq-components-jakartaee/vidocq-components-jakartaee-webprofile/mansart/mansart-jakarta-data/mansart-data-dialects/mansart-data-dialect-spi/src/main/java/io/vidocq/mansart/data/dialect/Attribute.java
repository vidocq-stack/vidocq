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
                io.vidocq.mansart.data.dialect.attribute.BooleanAttribute,
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
