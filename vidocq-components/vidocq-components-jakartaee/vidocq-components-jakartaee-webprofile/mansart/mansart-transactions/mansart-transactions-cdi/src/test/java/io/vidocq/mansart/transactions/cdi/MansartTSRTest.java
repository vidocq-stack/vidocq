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
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link MansartTSR}: the Jakarta Transactions synchronization registry over the transaction manager. */
class MansartTSRTest {

    private static final String KEY = "key";

    private final TransactionManager tm = new MansartTransactionManager();
    private final MansartTSR tsr = new MansartTSR(tm);

    @AfterEach
    void endTransaction() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) tm.rollback();
    }

    @Test
    void outsideATransactionThereIsNoKeyAndNoResource() {
        assertThat(tsr.getTransactionKey()).isNull();
        assertThat(tsr.getTransactionStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        assertThatThrownBy(() -> tsr.getResource(KEY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tsr.putResource(KEY, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(tsr::setRollbackOnly).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(tsr::getRollbackOnly).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tsr.registerInterposedSynchronization(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resourcesLiveForTheirTransaction() throws Exception {
        tm.begin();
        assertThat(tsr.getTransactionKey()).isSameAs(tm.getTransaction());
        assertThat(tsr.getResource(KEY)).isNull();
        tsr.putResource(KEY, "value");
        tsr.putResource("other", 2);
        assertThat(tsr.getResource(KEY)).isEqualTo("value");
        tm.commit();

        tm.begin();
        assertThat(tsr.getResource(KEY)).isNull();
    }

    @Test
    void rollbackOnlyAndInterposedSynchronizations() throws Exception {
        List<Integer> completions = new ArrayList<>();
        tm.begin();
        tsr.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                completions.add(-1);
            }

            @Override
            public void afterCompletion(int status) {
                completions.add(status);
            }
        });
        assertThat(tsr.getRollbackOnly()).isFalse();
        tsr.setRollbackOnly();
        assertThat(tsr.getRollbackOnly()).isTrue();
        assertThat(tsr.getTransactionStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        tm.rollback();

        assertThat(completions).containsExactly(Status.STATUS_ROLLEDBACK);
    }
}
