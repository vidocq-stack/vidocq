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
package io.vidocq.mansart.data.dialect.postgresql;

import io.vidocq.mansart.data.dialect.AbstractSqlDialect;
import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.SqlFragment;

import java.sql.SQLException;

/**
 * PostgreSQL dialect (M4). Mostly ANSI-compliant; the only structural divergence vs H2 is the
 * upsert form: {@code INSERT … ON CONFLICT ("id") DO UPDATE SET col = EXCLUDED.col}.
 */
public final class PostgresqlDialect extends AbstractSqlDialect {

    @Override public String name() { return "PostgreSQL"; }

    @Override protected String alwaysTrueSql() { return "TRUE"; }

    @Override protected String alwaysFalseSql() { return "FALSE"; }

    @Override
    public SqlFragment merge(EntityModel<?> model) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        StringBuilder set  = new StringBuilder();
        boolean firstCol = true;
        boolean firstSet = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (!firstCol) { cols.append(", "); vals.append(", "); }
            cols.append('"').append(a.columnName()).append('"');
            vals.append('?');
            firstCol = false;
            if (a == model.id()) continue;
            if (!firstSet) set.append(", ");
            set.append('"').append(a.columnName()).append("\" = EXCLUDED.\"")
               .append(a.columnName()).append('"');
            firstSet = false;
        }
        String sql = "INSERT INTO " + qualified(model)
                + " (" + cols + ") VALUES (" + vals + ") "
                + "ON CONFLICT (\"" + model.id().columnName() + "\") DO UPDATE SET " + set;
        return new SqlFragment(sql, java.util.List.of());
    }

    @Override
    public RuntimeException translate(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state)) {
            return new jakarta.data.exceptions.EntityExistsException(e.getMessage(), e);
        }
        if ("40001".equals(state)) {
            return new jakarta.data.exceptions.OptimisticLockingFailureException(e.getMessage(), e);
        }
        if (state != null && state.startsWith("23")) {
            return new jakarta.data.exceptions.MappingException(e.getMessage(), e);
        }
        return new io.vidocq.mansart.data.core.MansartDataException("PostgreSQL SQL error", e);
    }
}
