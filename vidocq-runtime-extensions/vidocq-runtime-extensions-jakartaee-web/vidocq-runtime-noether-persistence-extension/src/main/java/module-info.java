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
 * Jakarta Persistence 3.2 on the Vidocq runtime, with Noether: each persistence unit takes its connections from the
 * application's Mansart pool. Within a transaction, a JTA unit works on the connection Mansart Transactions keeps for
 * that transaction and that pool, which a Mansart Data repository on the same pool shares.
 */
module io.vidocq.runtime.extensions.jakartaee.web.noether.persistence {
    requires transitive io.vidocq.runtime.spi;
    // The application's entities carry @Entity, @Id, @PersistenceContext: the API reaches it through this module.
    requires transitive jakarta.persistence;
    requires io.vidocq.vauban.core;
    requires io.vidocq.noether.jpa.core;
    requires io.vidocq.noether.jpa.cdi;
    requires io.vidocq.mansart.transactions.jdbc;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.transaction;
    requires java.sql;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.NoetherPersistenceExtension;

    // VidocqPersistenceUnitBootstrap is created in-module by the generated provider: no `opens` to the container.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.extensions.jakartaee.web.noether.persistence._VaubanComponents;
}
