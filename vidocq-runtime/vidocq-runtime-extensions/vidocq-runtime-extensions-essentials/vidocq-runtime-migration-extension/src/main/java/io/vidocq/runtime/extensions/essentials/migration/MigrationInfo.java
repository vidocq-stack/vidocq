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
import java.util.Objects;

/**
 * The migrations of one datasource, as its backend lists them: those its schema history records as applied, and
 * those its locations hold that are still to run. The dev console's {@code migration} panel shows them as two
 * tables.
 *
 * @param applied the migrations the schema history records, oldest first; an immutable copy
 * @param pending the migrations still to run, in the order they would run; an immutable copy
 */
public record MigrationInfo(List<Migration> applied, List<Migration> pending) {

    public MigrationInfo {
        applied = List.copyOf(Objects.requireNonNull(applied, "applied"));
        pending = List.copyOf(Objects.requireNonNull(pending, "pending"));
    }

    /**
     * One migration, in words the page shows as they are.
     *
     * @param version     its version, such as {@code 3}, or for a Liquibase changeset, which has none, its id;
     *                    empty for a Flyway repeatable migration
     * @param description what it does, such as {@code create widget}, with a changeset's author
     * @param type        its kind, such as {@code SQL}, {@code JDBC} or {@code changeset}
     * @param installedOn when it was applied, ISO-8601, or empty for one still to run
     * @param state       what the backend says of it, such as {@code Success}, {@code Pending} or {@code Failed}
     */
    public record Migration(String version, String description, String type, String installedOn, String state) {

        public Migration {
            version = Objects.requireNonNullElse(version, "");
            description = Objects.requireNonNullElse(description, "");
            type = Objects.requireNonNullElse(type, "");
            installedOn = Objects.requireNonNullElse(installedOn, "");
            state = Objects.requireNonNullElse(state, "");
        }
    }
}
