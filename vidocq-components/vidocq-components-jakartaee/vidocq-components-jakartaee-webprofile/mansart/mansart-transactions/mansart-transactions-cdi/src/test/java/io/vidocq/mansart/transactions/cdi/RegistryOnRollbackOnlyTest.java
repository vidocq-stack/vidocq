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
package io.vidocq.mansart.transactions.cdi;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.transaction.Synchronization;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry serves a transaction marked rollback-only like any other: what it holds for the transaction is let
 * go when the transaction ends (MANSART-063).
 */
class RegistryOnRollbackOnlyTest {

    @Test
    void aResourceAndASynchronizationJoinATransactionMarkedRollbackOnly() throws Exception {
        var tm = new MansartTransactionManager();
        var registry = new MansartTSR(tm);
        List<Integer> outcomes = new ArrayList<>();
        tm.begin();
        tm.setRollbackOnly();

        registry.putResource("key", "value");
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override public void beforeCompletion() { /* nothing */ }
            @Override public void afterCompletion(int status) { outcomes.add(status); }
        });
        assertThat(registry.getResource("key")).isEqualTo("value");
        tm.rollback();

        assertThat(outcomes).containsExactly(jakarta.transaction.Status.STATUS_ROLLEDBACK);
        tm.begin();
        assertThat(registry.getResource("key")).isNull();
        tm.rollback();
    }
}
