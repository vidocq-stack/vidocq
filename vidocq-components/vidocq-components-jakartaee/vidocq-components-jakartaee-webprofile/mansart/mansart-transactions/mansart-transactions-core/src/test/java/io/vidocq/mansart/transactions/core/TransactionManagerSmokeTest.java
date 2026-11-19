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
package io.vidocq.mansart.transactions.core;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 — TDD seed for {@link MansartTransactionManager}. Every assertion currently fails because
 * the implementation is a skeleton. Drive the implementation by making each test go green in
 * order ; commit per test, refactor between.
 */
class TransactionManagerSmokeTest {

    private final TransactionManager tm = new MansartTransactionManager();

    @Test
    void newTransactionManagerHasNoActiveTransaction() throws Exception {
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        assertThat(tm.getTransaction()).isNull();
    }

    @Test
    void beginActivatesTransaction() throws Exception {
        tm.begin();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        assertThat(tm.getTransaction()).isNotNull();

        // Cleanup so other tests are not polluted by a lingering active TX on this thread.
        tm.rollback();
    }

    @Test
    void commitClearsTransaction() throws Exception {
        tm.begin();
        tm.commit();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        assertThat(tm.getTransaction()).isNull();
    }

    @Test
    void rollbackClearsTransaction() throws Exception {
        tm.begin();
        tm.rollback();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void beginTwiceFails() throws Exception {
        tm.begin();
        try {
            assertThatThrownBy(tm::begin)
                    .isInstanceOf(jakarta.transaction.NotSupportedException.class);
        } finally {
            tm.rollback();
        }
    }
}
