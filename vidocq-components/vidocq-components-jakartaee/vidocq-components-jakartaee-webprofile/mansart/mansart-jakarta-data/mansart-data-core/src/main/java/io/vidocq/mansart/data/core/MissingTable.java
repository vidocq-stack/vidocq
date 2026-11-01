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

import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.SqlNames;

/**
 * The error a repository raises when its entity's table does not exist (MANSART-036): it names the entity, the table
 * Mansart looked for, how that name was chosen, and the two ways out. Mansart's default table name is not Jakarta
 * Persistence's, so a table created after the entity name is the usual cause.
 */
final class MissingTable {

    private MissingTable() {
    }

    static MansartDataException of(EntityModel<?> model, String qualifiedTable, String hint, Throwable cause) {
        Class<?> entity = model.entityClass();
        String simple = entity.getSimpleName();
        String table = model.tableName();
        boolean byDefault = table.equals(SqlNames.tableName(simple));
        StringBuilder message = new StringBuilder()
                .append("The table ").append(qualifiedTable).append(" of entity ").append(entity.getName())
                .append(" does not exist.");
        if (byDefault) {
            message.append(" Mansart names an entity's table after its class, in snake_case and plural (")
                    .append(simple).append(" → ").append(table)
                    .append("), not after the entity name as Jakarta Persistence does by default;")
                    .append(" @Entity(name = …) does not change it.")
                    .append(" Create the table (with Flyway, a migration in src/main/resources/db/migration),")
                    .append(" or give the existing one with @Table(name = \"…\") on ").append(simple).append('.');
        } else {
            message.append(" It is the table named by its @Table annotation.")
                    .append(" Create it (with Flyway, a migration in src/main/resources/db/migration),")
                    .append(" or correct @Table(name = \"…\") on ").append(simple).append('.');
        }
        if (hint != null) {
            message.append(' ').append(hint);
        }
        return new MansartDataException(message.toString(), cause);
    }
}
