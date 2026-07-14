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
 * Vidocq extension that fails fast on boot if Mansart Transactions has not wired its
 * {@link jakarta.transaction.TransactionManager} bean into CDI.
 *
 * <p>The actual {@code @Transactional} interceptor and {@code @TransactionScoped} context come
 * from {@code mansart-transactions-cdi}, auto-discovered by Vauban via
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}.
 * This extension does not register the BCE itself; it only validates wiring at boot.
 */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    requires transitive io.vidocq.mansart.transactions.cdi;
    requires jakarta.cdi;
    // MANSART-007 phase 2 — boot-time recovery scan: XA-capable DataSource beans contribute
    // their driver XAResource to MansartTransactionManager.recover().
    requires jakarta.transaction;
    requires java.sql;
    requires java.transaction.xa;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions.MansartTransactionsIntegrationExtension;
}
