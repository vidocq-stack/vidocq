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
package io.vidocq.runtime.extensions.essentials.migration;

import java.util.List;

/**
 * One datasource to migrate. {@code locations} are Flyway locations or, for Liquibase, the changelog
 * path in element 0; an empty list means "use the backend default".
 *
 * <p>It carries the datasource's password for the backend to connect with: {@link #toString()} masks it, and nothing
 * that goes to the log, the startup report or the dev console ever writes it.
 *
 * @param failOnMissingLocations {@code vidocq.migration.failOnMissingLocations}: fail the boot when a
 *                               location holds nothing, instead of migrating nothing
 * @param cleanDisabled          {@code vidocq.migration[.<name>].cleanDisabled}: refuse to drop the schema's objects,
 *                               as the dev console's {@code clean-and-migrate} action asks; {@code true} unless the
 *                               application set it to {@code false}
 */
public record MigrationTarget(String dataSourceName, String jdbcUrl, String username, String password,
                              List<String> locations, boolean failOnMissingLocations, boolean cleanDisabled) {

    /** A target whose schema is never cleaned. */
    public MigrationTarget(String dataSourceName, String jdbcUrl, String username, String password,
                           List<String> locations, boolean failOnMissingLocations) {
        this(dataSourceName, jdbcUrl, username, password, locations, failOnMissingLocations, true);
    }

    /** A target that migrates nothing, rather than failing, when a location holds nothing. */
    public MigrationTarget(String dataSourceName, String jdbcUrl, String username, String password,
                           List<String> locations) {
        this(dataSourceName, jdbcUrl, username, password, locations, false);
    }

    /** The components, the password masked: {@code ***} when there is one, {@code null} otherwise. */
    @Override
    public String toString() {
        return "MigrationTarget[dataSourceName=" + dataSourceName + ", jdbcUrl=" + jdbcUrl + ", username=" + username
                + ", password=" + (password == null ? null : "***") + ", locations=" + locations
                + ", failOnMissingLocations=" + failOnMissingLocations + ", cleanDisabled=" + cleanDisabled + "]";
    }
}
