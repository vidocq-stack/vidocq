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
package io.vidocq.mansart.data.dialect.h2;

import io.vidocq.mansart.data.dialect.AbstractSqlDialect;
import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.SqlFragment;

import java.sql.SQLException;

/**
 * H2 dialect (M3a). Generates ANSI-leaning SQL using H2's {@code MERGE INTO ... KEY (...)} for
 * upsert and {@code IDENTITY} columns for generated keys.
 */
public final class H2Dialect extends AbstractSqlDialect {

    @Override public String name() { return "H2"; }

    @Override protected String alwaysTrueSql() { return "1=1"; }

    @Override protected String alwaysFalseSql() { return "1=0"; }

    @Override
    public SqlFragment merge(EntityModel<?> model) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        boolean first = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (!first) { cols.append(", "); vals.append(", "); }
            cols.append('"').append(a.columnName()).append('"');
            vals.append('?');
            first = false;
        }
        String sql = "MERGE INTO " + qualified(model)
                + " (" + cols + ") KEY (\"" + model.id().columnName() + "\") VALUES (" + vals + ")";
        return new SqlFragment(sql, java.util.List.of());
    }

    @Override
    public RuntimeException translate(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state) || "23001".equals(state)) {
            return new jakarta.data.exceptions.EntityExistsException(e.getMessage(), e);
        }
        if ("40001".equals(state)) {
            return new jakarta.data.exceptions.OptimisticLockingFailureException(e.getMessage(), e);
        }
        if (state != null && state.startsWith("23")) {
            return new jakarta.data.exceptions.MappingException(e.getMessage(), e);
        }
        return new io.vidocq.mansart.data.core.MansartDataException("H2 SQL error", e);
    }
}
