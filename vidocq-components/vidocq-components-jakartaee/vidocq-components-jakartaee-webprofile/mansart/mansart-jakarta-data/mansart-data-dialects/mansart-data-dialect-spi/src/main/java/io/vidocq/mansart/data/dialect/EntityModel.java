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

import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;

/**
 * Compile-time-resolved model of one entity. Built once at the {@code <clinit>} of the generated
 * {@code _Entity} metamodel class. Immutable, thread-safe, AOT-friendly.
 *
 * <p>{@link #constructor()} is a {@code MethodHandle} for the entity's no-arg constructor obtained
 * via {@code MethodHandles.privateLookupIn(...)} in {@code _Entity.<clinit>}. Used by the row mapper
 * to materialize entity instances without reflection.
 */
public record EntityModel<E>(
        Class<E> entityClass,
        String tableName,
        String schema,
        IdAttribute<E, ?> id,
        Optional<VersionAttribute<E, ?>> version,
        List<Attribute<E, ?>> attributes,
        MethodHandle constructor
) {

    public EntityModel {
        attributes = List.copyOf(attributes);
    }

    public Optional<Attribute<E, ?>> attribute(String name) {
        for (Attribute<E, ?> a : attributes) {
            if (a.name().equals(name)) return Optional.of(a);
        }
        return Optional.empty();
    }
}
