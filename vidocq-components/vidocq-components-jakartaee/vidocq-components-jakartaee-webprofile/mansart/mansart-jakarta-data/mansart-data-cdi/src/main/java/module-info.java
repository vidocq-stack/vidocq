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
/**
 * CDI 4.1 bootstrap for Mansart Data — BuildCompatibleExtension reading {@code META-INF/mansart-repositories.list}.
 * Implementation in M3.
 */
module io.vidocq.mansart.data.cdi {
    requires transitive io.vidocq.mansart.data.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.data;        // M7-4 — @Enhancement uses Repository.class in the typed BCE API
    requires java.sql;            // javax.sql.DataSource, javax.sql.XADataSource
    requires java.naming;         // M9 — JNDI lookup for dataStore values starting with "java:"
    // MANSART-007 — JTA transaction bridge, active only when a TransactionManager is deployed.
    // Static requirements, so that a deployment without transactions (e.g. the Jakarta Data TCK
    // on Weld) keeps working: JtaBridgeActivator probes both APIs by name before it loads any
    // class that uses them.
    requires static jakarta.transaction;
    requires static io.vidocq.mansart.transactions.jdbc;

    exports io.vidocq.mansart.data.cdi;

    // Required with the `provides` below: supplies the VaubanComponentProvider service type.
    requires io.vidocq.vauban.api;

    // No `opens` (mansart#35): the default RepositoryRuntime producer is instantiated and its
    // producer method invoked in-module by the APT-generated _VaubanComponents provider below; the
    // synthetic-bean creators are public classes of this exported package. Other CDI containers
    // run on a class path, where the descriptor is ignored.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.mansart.data.cdi._VaubanComponents;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mansart.data.cdi.MansartDataExtension;
}
