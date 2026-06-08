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
