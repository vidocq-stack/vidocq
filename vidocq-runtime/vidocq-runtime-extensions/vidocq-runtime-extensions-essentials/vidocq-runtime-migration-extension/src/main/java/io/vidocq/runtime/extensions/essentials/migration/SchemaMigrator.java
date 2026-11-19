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
 * A schema-migration backend. Discovered via {@link java.util.ServiceLoader}; an application puts
 * exactly one provider (the Flyway or the Liquibase extension) on its path.
 */
public interface SchemaMigrator {
    /** Backend id, e.g. {@code "flyway"} or {@code "liquibase"}. */
    String engine();

    /** Runs the migration against {@code target}; throws to abort the boot on failure. */
    MigrationResult migrate(MigrationTarget target);

    /**
     * Where the backend looks when a target names no location, as the startup report prints it, such as
     * {@code classpath:db/migration}.
     *
     * @return the locations; empty by default, for a backend that does not say
     */
    default List<String> defaultLocations() {
        return List.of();
    }

    /**
     * The migrations of {@code target}: applied and pending. Opens a connection of its own, as
     * {@link #migrate} does, so it is never called while the dev console samples: the extension lists them once
     * after the boot's migration, in a dev launch, and again after each action of the console.
     *
     * @param target the datasource
     * @return what its schema history records and what its locations still hold
     * @throws UnsupportedOperationException by default, for a backend that cannot list them
     */
    default MigrationInfo info(MigrationTarget target) {
        throw new UnsupportedOperationException(engine() + " cannot list the migrations");
    }

    /**
     * Drops every object in the schema of {@code target}: its tables, its views, its schema history. Called by
     * the dev console's {@code clean-and-migrate} action only, in a dev launch, and only for a target whose
     * {@link MigrationTarget#cleanDisabled() cleanDisabled()} is {@code false}; an implementation refuses, throwing
     * {@link IllegalStateException}, when it is {@code true}.
     *
     * @param target the datasource
     * @throws UnsupportedOperationException by default, for a backend that cannot clean a schema
     */
    default void clean(MigrationTarget target) {
        throw new UnsupportedOperationException(engine() + " cannot clean a schema");
    }
}
