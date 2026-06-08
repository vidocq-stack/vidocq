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
package io.vidocq.mansart.transactions.tck;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the Mansart TCK harness. Verifies that the provider exposes the expected
 * singletons and that a minimal begin/commit cycle succeeds. This is the equivalent of the
 * "assert wiring works" check that the official TCK runs first; keeping it outside the full suite
 * avoids running the entire TCK just to validate a provider regression.
 *
 * <p>Executed by default with {@code mvn test} (default profile,
 * includes=MansartTckSmoke*). The {@code tck-run} profile additionally enables official TCK jar
 * scanning.
 */
class MansartTckSmokeTest {

    @Test
    void providerExposesNonNullTransactionManager() {
        TransactionManager tm = MansartTckProvider.getTransactionManager();
        assertThat(tm).isNotNull();
    }

    @Test
    void providerExposesNonNullUserTransaction() {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        assertThat(ut).isNotNull();
    }

    @Test
    void userTransactionBeginCommitCycle() throws Exception {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        ut.begin();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        ut.commit();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void userTransactionRollbackCycle() throws Exception {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        ut.begin();
        ut.rollback();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void singletonAcrossLookups() {
        // The TCK often makes N calls — the provider must return the same TM (otherwise
        // the ThreadLocal for active state doesn't work across phases).
        assertThat(MansartTckProvider.getTransactionManager())
                .isSameAs(MansartTckProvider.getTransactionManager());
    }
}
