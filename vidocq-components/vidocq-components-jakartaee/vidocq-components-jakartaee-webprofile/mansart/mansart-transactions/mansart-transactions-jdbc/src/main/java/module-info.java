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
 * JDBC adapter for Mansart Transactions — wraps a plain {@link java.sql.Connection} as an
 * {@link javax.transaction.xa.XAResource} so it can participate in a Mansart-coordinated
 * transaction without requiring a {@code XADataSource} driver.
 *
 * <p>Limitation : 1PC only — multi-resource 2PC requires a real XA driver.
 */
module io.vidocq.mansart.transactions.jdbc {
    requires transitive io.vidocq.mansart.transactions.core;
    requires java.sql;
    requires java.transaction.xa;

    exports io.vidocq.mansart.transactions.jdbc;
}
