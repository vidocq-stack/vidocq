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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions;

import io.vidocq.mansart.transactions.core.FileRecoveryLog;
import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.mansart.transactions.core.RecoveryLog;
import io.vidocq.runtime.spi.Suspendable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The recovery journal holds an open file: it is released before a checkpoint and taken again after. */
class TransactionsSuspendTest {

    private static final javax.transaction.xa.Xid XID = new javax.transaction.xa.Xid() {
        @Override public int getFormatId() { return 1; }
        @Override public byte[] getGlobalTransactionId() { return new byte[]{1}; }
        @Override public byte[] getBranchQualifier() { return new byte[]{0}; }
    };

    @Test
    void theExtensionIsSuspendable() {
        assertInstanceOf(Suspendable.class, new MansartTransactionsIntegrationExtension());
    }

    @Test
    void withoutADurableManagerThereIsNothingToDo() {
        var extension = new MansartTransactionsIntegrationExtension();
        assertDoesNotThrow(extension::suspend);
        assertDoesNotThrow(extension::resume);
    }

    @Test
    void suspendReleasesTheJournalFile_resumeTakesItAgain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        var log = new FileRecoveryLog(file);
        var extension = new MansartTransactionsIntegrationExtension();
        extension.adopt(new MansartTransactionManager(log));

        extension.suspend();
        Path other = dir.resolve("other.log");
        Files.writeString(other, "");
        Files.move(other, file, StandardCopyOption.REPLACE_EXISTING);
        extension.resume();
        log.append(new RecoveryLog.Record(RecoveryLog.Type.COMPLETED,
                XID));

        assertEquals(1, log.scan().size());
        log.close();
    }
}
