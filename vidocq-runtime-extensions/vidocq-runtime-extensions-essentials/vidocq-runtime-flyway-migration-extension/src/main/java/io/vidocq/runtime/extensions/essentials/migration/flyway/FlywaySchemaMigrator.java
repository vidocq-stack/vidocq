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
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Flyway-backed {@link SchemaMigrator}. */
public final class FlywaySchemaMigrator implements SchemaMigrator {

    private static final String DEFAULT_LOCATION = "classpath:db/migration";

    @Override
    public String engine() {
        return "flyway";
    }

    @Override
    public MigrationResult migrate(MigrationTarget t) {
        String[] locations = t.locations().isEmpty()
                ? new String[]{DEFAULT_LOCATION}
                : t.locations().toArray(String[]::new);
        MigrateResult r = Flyway.configure(getClass().getClassLoader())
                .dataSource(t.jdbcUrl(), t.username(), t.password())
                .locations(locations)
                .load()
                .migrate();
        return new MigrationResult(r.migrationsExecuted,
                r.targetSchemaVersion == null ? "(none)" : r.targetSchemaVersion);
    }
}
