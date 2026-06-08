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
package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Materializes one entity instance from the current row of a {@link ResultSet}.
 *
 * <p>Columns are read in the same order as {@link EntityModel#attributes()} — the dialect produces
 * SQL with that exact column ordering, so the mapper just iterates and binds.
 *
 * <p>Reference attributes (M3a) are skipped: their FK column is read into a stub instance with the
 * id field set, but no lazy proxy is installed and no eager fetch is performed. Full handling lands
 * in M3b together with relation traversal.
 */
final class RowMapper {

    private RowMapper() {}

    @SuppressWarnings("unchecked")
    static <E> E map(EntityModel<E> model, Dialect dialect, ResultSet rs) throws SQLException {
        E entity;
        try {
            entity = (E) model.constructor().invoke();
        } catch (Throwable t) {
            throw new MansartDataException("Failed to instantiate " + model.entityClass(), t);
        }
        int idx = 1;
        for (Attribute<E, ?> a : model.attributes()) {
            // M8-3 — ReferenceAttribute columns hold the FK id, not the target entity. Read as
            // Long (most common id type) — fully materialising the related entity would require
            // an eager fetch (out of scope here; stays a M3a stub).
            Class<?> readType = (a instanceof ReferenceAttribute<?, ?>) ? Long.class : a.javaType();
            Object value = dialect.extract(rs, idx++, readType);
            if (value == null) continue;
            if (a instanceof ReferenceAttribute<?, ?>) {
                // The FK id is in `value` but we do not materialize a stub yet.
                continue;
            }
            try {
                ((Attribute<E, Object>) a).setter().invoke(entity, value);
            } catch (Throwable t) {
                throw new MansartDataException("Failed to set " + a.name() + " on " + model.entityClass(), t);
            }
        }
        return entity;
    }
}
