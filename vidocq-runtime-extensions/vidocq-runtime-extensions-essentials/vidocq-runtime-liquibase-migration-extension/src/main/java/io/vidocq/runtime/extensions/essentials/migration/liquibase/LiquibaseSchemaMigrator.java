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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;

/** Liquibase-backed {@link SchemaMigrator}. {@code locations.get(0)} is the changelog path. */
public final class LiquibaseSchemaMigrator implements SchemaMigrator {

    private static final String DEFAULT_CHANGELOG = "db/changelog/db.changelog-master.xml";

    @Override
    public String engine() {
        return "liquibase";
    }

    @Override
    public MigrationResult migrate(MigrationTarget t) {
        String changelog = t.locations().isEmpty() ? DEFAULT_CHANGELOG : t.locations().get(0);
        try (Connection conn = DriverManager.getConnection(t.jdbcUrl(), t.username(), t.password())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(changelog,
                    new ClassLoaderResourceAccessor(getClass().getClassLoader()), database)) {
                int toRun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()).size();
                liquibase.update(new Contexts(), new LabelExpression());
                return new MigrationResult(toRun, "(liquibase)");
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Liquibase migration failed for '" + t.dataSourceName() + "': " + e.getMessage(), e);
        }
    }
}
