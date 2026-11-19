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
 * What one datasource's migration did, for the log line and the startup report.
 *
 * @param applied      the migrations or changesets run
 * @param version      the schema version the datasource is at afterwards: the new one, the current one when
 *                     nothing was left to run, {@code (none)} when it has none
 * @param nothingFound the backend found no migration at its locations and the schema history records none
 *                     either, so the schema was not migrated at all: reported as {@code VIDOCQ-MIG-001}.
 *                     {@code false} when the backend cannot tell
 */
public record MigrationResult(int applied, String version, boolean nothingFound) {

    /**
     * A result that does not say whether nothing was found, as a backend written before
     * {@link #nothingFound()} returns it: never reported as {@code VIDOCQ-MIG-001}.
     */
    public MigrationResult(int applied, String version) {
        this(applied, version, false);
    }
}
