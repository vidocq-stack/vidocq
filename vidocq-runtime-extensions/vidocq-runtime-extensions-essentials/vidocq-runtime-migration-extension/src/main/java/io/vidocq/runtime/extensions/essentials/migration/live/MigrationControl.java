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
package io.vidocq.runtime.extensions.essentials.migration.live;

import io.vidocq.runtime.extensions.essentials.migration.MigrationInfo;

import java.util.List;

/**
 * What the migration panel of the -dev module shows and does (Vidocq/vidocq#143). The runtime extension implements
 * it: the operations and their locks stay there.
 */
public interface MigrationControl {

    /** One datasource as the panel shows it. */
    record LiveOutcome(String dataSource, String version, String lastRun, boolean cleanDisabled,
                       String cleanDisabledKey, MigrationInfo info, String infoAbsent) {}

    List<LiveOutcome> outcomes();

    /** The datasource names the actions accept, in configuration order; empty when there is no migrator. */
    List<String> dataSources();

    /** Migrates {@code dataSource}; the line the action returns. */
    String migrateNow(String dataSource);

    /** Cleans and migrates {@code dataSource}, or says why it refused; the line the action returns. */
    String cleanAndMigrate(String dataSource);
}
