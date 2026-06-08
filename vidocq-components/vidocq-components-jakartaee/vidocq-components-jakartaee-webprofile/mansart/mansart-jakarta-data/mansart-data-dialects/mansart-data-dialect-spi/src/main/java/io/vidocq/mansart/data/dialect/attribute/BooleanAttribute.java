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
