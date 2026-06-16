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

/**
 * A schema-migration backend. Discovered via {@link java.util.ServiceLoader}; an application puts
 * exactly one provider (the Flyway or the Liquibase extension) on its path.
 */
public interface SchemaMigrator {
    /** Backend id, e.g. {@code "flyway"} or {@code "liquibase"}. */
    String engine();

    /** Runs the migration against {@code target}; throws to abort the boot on failure. */
    MigrationResult migrate(MigrationTarget target);
}
