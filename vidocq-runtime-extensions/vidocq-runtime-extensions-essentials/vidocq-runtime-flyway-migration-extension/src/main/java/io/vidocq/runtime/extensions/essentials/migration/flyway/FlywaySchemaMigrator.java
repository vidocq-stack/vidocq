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
import io.vidocq.runtime.spi.ApplicationLayer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Flyway-backed {@link SchemaMigrator}.
 *
 * <p>Flyway loads scripts, drivers and Java migrations through the application's class loader, the thread
 * context class loader. When Vidocq booted the application in a module layer of its own, that loader lists no
 * directory, so the {@code classpath:} locations are listed from the layer's modules instead
 * ({@link ApplicationLayerMigrations}, vidocq#96).
 */
public final class FlywaySchemaMigrator implements SchemaMigrator {

    private static final System.Logger LOG = System.getLogger(FlywaySchemaMigrator.class.getName());
    private static final String DEFAULT_LOCATION = "classpath:db/migration";

    @Override
    public String engine() {
        return "flyway";
    }

    @Override
    public List<String> defaultLocations() {
        return List.of(DEFAULT_LOCATION);
    }

    @Override
    public MigrationResult migrate(MigrationTarget t) {
        Function<String, List<String>> lister = ApplicationLayer.current()
                .<Function<String, List<String>>>map(layer -> directory -> ApplicationLayer.list(layer, directory))
                .orElse(null);
        return migrate(t, lister, applicationLoader());
    }

    /**
     * {@link #migrate(MigrationTarget)} with the application's files and loader given, for the tests.
     *
     * @param lister the files under a directory of the application layer, or {@code null} without a layer,
     *               where Flyway's own scanner lists the locations
     * @param loader the loader Flyway reads scripts and loads classes with
     */
    MigrationResult migrate(MigrationTarget t, Function<String, List<String>> lister, ClassLoader loader) {
        String[] locations = t.locations().isEmpty()
                ? new String[]{DEFAULT_LOCATION}
                : t.locations().toArray(String[]::new);
        FluentConfiguration configuration = Flyway.configure(loader)
                .dataSource(t.jdbcUrl(), t.username(), t.password())
                .locations(locations)
                .failOnMissingLocations(t.failOnMissingLocations());
        if (lister != null) {
            listFromTheApplicationLayer(configuration, lister, t);
        }
        Flyway flyway = configuration.load();
        MigrateResult r = flyway.migrate();
        // Nothing ran and there was no version before: either nothing was found, or the history holds
        // repeatable migrations only, which have no version. Only the second look tells them apart.
        boolean nothingFound = r.migrationsExecuted == 0 && r.initialSchemaVersion == null
                && flyway.info().all().length == 0;
        return new MigrationResult(r.migrationsExecuted, version(r), nothingFound);
    }

    /** The version after the migration: the new one, else the one the schema was already at. */
    static String version(MigrateResult r) {
        if (r.targetSchemaVersion != null) {
            return r.targetSchemaVersion;
        }
        return r.initialSchemaVersion != null ? r.initialSchemaVersion : "(none)";
    }

    private static void listFromTheApplicationLayer(FluentConfiguration configuration,
                                                    Function<String, List<String>> lister, MigrationTarget t) {
        Location[] locations = configuration.getLocations();
        if (!Arrays.stream(locations).allMatch(Location::isClassPath)) {
            LOG.log(System.Logger.Level.WARNING, "Datasource " + t.dataSourceName() + ": the locations "
                    + Arrays.toString(locations) + " are not all classpath:, so Flyway scans them itself, and its"
                    + " scanner cannot list the classpath: ones in the application layer");
            return;
        }
        ApplicationLayerMigrations migrations = new ApplicationLayerMigrations(locations, lister,
                configuration.getClassLoader(), configuration.getEncoding(), configuration.isFailOnMissingLocations());
        configuration.resourceProvider(migrations).javaMigrationClassProvider(migrations);
    }

    /** The context class loader, the application's; the extension's own when there is none. */
    static ClassLoader applicationLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : FlywaySchemaMigrator.class.getClassLoader();
    }
}
