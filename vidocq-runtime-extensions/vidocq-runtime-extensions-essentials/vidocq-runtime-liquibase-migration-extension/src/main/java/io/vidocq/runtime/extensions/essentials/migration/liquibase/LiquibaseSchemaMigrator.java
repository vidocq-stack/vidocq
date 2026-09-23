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

import io.vidocq.runtime.extensions.essentials.migration.MigrationInfo;
import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
import io.vidocq.runtime.spi.ApplicationLayer;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Date;
import java.util.List;

/**
 * Liquibase-backed {@link SchemaMigrator}. {@code locations.get(0)} is the changelog path.
 *
 * <p>Changelogs are read through the application's class loader, the thread context class loader. When Vidocq
 * booted the application in a module layer of its own, they are read by name and listed from the layer
 * ({@link ApplicationResourceAccessor}, vidocq#96).
 */
public final class LiquibaseSchemaMigrator implements SchemaMigrator {

    private static final String DEFAULT_CHANGELOG = "db/changelog/db.changelog-master.xml";

    @Override
    public String engine() {
        return "liquibase";
    }

    @Override
    public List<String> defaultLocations() {
        return List.of(DEFAULT_CHANGELOG);
    }

    @Override
    public MigrationResult migrate(MigrationTarget t) {
        return migrate(t, currentResources());
    }

    /** {@link #migrate(MigrationTarget)} with the changelogs read from {@code resources}, for the tests. */
    MigrationResult migrate(MigrationTarget t, ResourceAccessor resources) {
        return withLiquibase(t, resources, "migration", liquibase -> {
            int toRun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()).size();
            // nothing to run, and nothing ever run: the changelog holds no changeset at all
            boolean nothingFound = toRun == 0 && liquibase.getDatabase().getRanChangeSetList().isEmpty();
            liquibase.update(new Contexts(), new LabelExpression());
            return new MigrationResult(toRun, "(liquibase)", nothingFound);
        });
    }

    /**
     * The changesets the {@code DATABASECHANGELOG} table records, and those of the changelog still to run. A
     * changeset has no version: its id stands in the version column, its author in the description.
     */
    @Override
    public MigrationInfo info(MigrationTarget t) {
        return info(t, currentResources());
    }

    /** {@link #info(MigrationTarget)} with the changelogs read from {@code resources}, for the tests. */
    MigrationInfo info(MigrationTarget t, ResourceAccessor resources) {
        return withLiquibase(t, resources, "info", liquibase -> {
            List<MigrationInfo.Migration> applied = liquibase.getDatabase().getRanChangeSetList().stream()
                    .map(LiquibaseSchemaMigrator::applied)
                    .toList();
            List<MigrationInfo.Migration> pending = liquibase.listUnrunChangeSets(new Contexts(),
                            new LabelExpression()).stream()
                    .map(LiquibaseSchemaMigrator::pending)
                    .toList();
            return new MigrationInfo(applied, pending);
        });
    }

    /**
     * Liquibase's {@code dropAll}, which drops every object of the default schema, the {@code DATABASECHANGELOG}
     * table included. Liquibase has no switch of its own that refuses it: the target's {@code cleanDisabled} is
     * that switch, and it refuses unless it is {@code false}, with nothing dropped.
     *
     * @throws IllegalStateException when the target's {@code cleanDisabled} is {@code true}
     */
    @Override
    public void clean(MigrationTarget t) {
        clean(t, currentResources());
    }

    /** {@link #clean(MigrationTarget)} with the changelogs read from {@code resources}, for the tests. */
    void clean(MigrationTarget t, ResourceAccessor resources) {
        if (t.cleanDisabled()) {
            throw new IllegalStateException("clean is disabled for datasource " + t.dataSourceName());
        }
        withLiquibase(t, resources, "clean", liquibase -> {
            liquibase.dropAll();
            return null;
        });
    }

    /** What Liquibase does with an open {@link Liquibase}. */
    @FunctionalInterface
    private interface Work<R> {
        R run(Liquibase liquibase) throws Exception;
    }

    /** Runs {@code work} on a connection of its own to {@code t}, closed afterwards. */
    private static <R> R withLiquibase(MigrationTarget t, ResourceAccessor resources, String what, Work<R> work) {
        String changelog = t.locations().isEmpty() ? DEFAULT_CHANGELOG : t.locations().get(0);
        try (Connection conn = DriverManager.getConnection(t.jdbcUrl(), t.username(), t.password())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(changelog, resources, database)) {
                return work.run(liquibase);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Liquibase " + what + " failed for '" + t.dataSourceName() + "': " + e.getMessage(), e);
        }
    }

    private static ResourceAccessor currentResources() {
        ClassLoader loader = applicationLoader();
        return ApplicationLayer.current()
                .<ResourceAccessor>map(layer ->
                        new ApplicationResourceAccessor(directory -> ApplicationLayer.list(layer, directory), loader))
                .orElseGet(() -> new ClassLoaderResourceAccessor(loader));
    }

    private static MigrationInfo.Migration applied(RanChangeSet ran) {
        return new MigrationInfo.Migration(ran.getId(), describe(ran.getAuthor(), ran.getDescription()),
                "changeset", instant(ran.getDateExecuted()),
                ran.getExecType() == null ? "" : ran.getExecType().name());
    }

    private static MigrationInfo.Migration pending(ChangeSet changeSet) {
        return new MigrationInfo.Migration(changeSet.getId(),
                describe(changeSet.getAuthor(), changeSet.getDescription()), "changeset", "", "PENDING");
    }

    private static String describe(String author, String description) {
        String by = author == null || author.isBlank() ? "" : "by " + author;
        if (description == null || description.isBlank()) {
            return by;
        }
        return by.isEmpty() ? description : description + " (" + by + ")";
    }

    private static String instant(Date executed) {
        return executed == null ? "" : executed.toInstant().toString();
    }

    /** The context class loader, the application's; the extension's own when there is none. */
    static ClassLoader applicationLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : LiquibaseSchemaMigrator.class.getClassLoader();
    }
}
