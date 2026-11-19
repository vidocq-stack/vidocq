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
package io.vidocq.mansart.transactions.jdbc;

import org.junit.jupiter.api.Test;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@link XAResource} contract of {@link ConnectionXAResource} outside a transaction manager. */
class ConnectionXAResourceContractTest {

    private static Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:xa-" + UUID.randomUUID(), "sa", "");
    }

    @Test
    void reportsALocalResource() throws Exception {
        try (Connection c = open()) {
            var resource = new ConnectionXAResource(c);

            assertThat(resource.prepare(null)).isEqualTo(XAResource.XA_OK);
            assertThat(resource.recover(XAResource.TMSTARTRSCAN)).isEmpty();
            assertThat(resource.isSameRM(resource)).isTrue();
            assertThat(resource.isSameRM(new ConnectionXAResource(c))).isFalse();
            assertThat(resource.setTransactionTimeout(30)).isTrue();
            assertThat(resource.getTransactionTimeout()).isEqualTo(30);
            resource.forget(null);
            resource.end(null, XAResource.TMSUCCESS);
            assertThat(c.getAutoCommit()).isTrue();
        }
    }

    @Test
    void startSuspendsAutoCommitAndCompletionRestoresIt() throws Exception {
        try (Connection c = open()) {
            var resource = new ConnectionXAResource(c);
            resource.start(null, XAResource.TMNOFLAGS);
            assertThat(c.getAutoCommit()).isFalse();
            resource.commit(null, true);
            assertThat(c.getAutoCommit()).isTrue();

            c.setAutoCommit(false);
            resource.start(null, XAResource.TMNOFLAGS);
            resource.rollback(null);
            assertThat(c.getAutoCommit()).isFalse();
        }
    }

    @Test
    void reportsDriverFailuresAsXaErrors() throws Exception {
        Connection c = open();
        var resource = new ConnectionXAResource(c);
        resource.start(null, XAResource.TMNOFLAGS);
        c.close();

        assertThatThrownBy(() -> resource.commit(null, true))
                .isInstanceOfSatisfying(XAException.class, e -> assertThat(e.errorCode).isEqualTo(XAException.XA_RBROLLBACK))
                .hasCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> resource.commit(null, false))
                .isInstanceOfSatisfying(XAException.class, e -> assertThat(e.errorCode).isEqualTo(XAException.XAER_RMERR));
        assertThatThrownBy(() -> resource.rollback(null))
                .isInstanceOfSatisfying(XAException.class, e -> assertThat(e.errorCode).isEqualTo(XAException.XAER_RMERR));
        assertThatThrownBy(() -> resource.start(null, XAResource.TMNOFLAGS))
                .isInstanceOfSatisfying(XAException.class, e -> assertThat(e.errorCode).isEqualTo(XAException.XAER_RMERR));
    }
}
