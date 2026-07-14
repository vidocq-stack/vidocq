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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANSART-007 phase 2 — the CDI producer's TM is volatile by default and durable
 * ({@code FileRecoveryLog}) when {@code mansart.tx.recovery.log} names a journal path.
 */
class DurableTmBootstrapTest {

    @TempDir
    Path dir;

    @Test
    void noPropertyMeansVolatileTm() {
        MansartTransactionManager tm = MansartTransactionsProducer.bootstrapTm(null);
        assertFalse(tm.durable(), "without a journal path the TM must stay volatile");
        assertFalse(MansartTransactionsProducer.bootstrapTm("  ").durable());
    }

    @Test
    void journalPathMeansDurableTm() throws Exception {
        Path journal = dir.resolve("tx-recovery.log");
        MansartTransactionManager tm = MansartTransactionsProducer.bootstrapTm(journal.toString());

        assertTrue(tm.durable(), "a journal path must produce a durable TM");
        assertTrue(Files.exists(journal), "the journal file is created eagerly");
    }

    @Test
    void unwritableJournalFailsLoudly() {
        // A DIRECTORY at the journal path cannot be opened for append — missing parent
        // directories, by contrast, are created (FileRecoveryLog.createDirectories).
        assertThrows(IllegalStateException.class,
                () -> MansartTransactionsProducer.bootstrapTm(dir.toString()),
                "an unopenable journal is a boot error, not a silent volatile fallback");
    }
}
