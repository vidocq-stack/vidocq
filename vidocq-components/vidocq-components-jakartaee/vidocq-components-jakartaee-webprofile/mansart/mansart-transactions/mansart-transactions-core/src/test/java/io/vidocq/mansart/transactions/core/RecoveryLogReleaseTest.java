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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.Xid;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A process snapshot (CRaC checkpoint) refuses an open file: the journal lets go of its file
 * ({@link RecoveryLog#release()}) and takes it again ({@link RecoveryLog#reopen()}).
 */
class RecoveryLogReleaseTest {

    private static RecoveryLog.Record prepared(int id) {
        Xid xid = new MansartXid(new byte[]{(byte) id}, new byte[]{0});
        return new RecoveryLog.Record(RecoveryLog.Type.PREPARED, xid);
    }

    @Test
    void releasedJournalHoldsNoFile_reopenAppendsToTheFileAtThePath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        try (var log = new FileRecoveryLog(file)) {
            log.append(prepared(1));

            // A new file at the same path: an open channel keeps writing to the old one.
            log.release();
            Path other = dir.resolve("other.log");
            Files.writeString(other, "");
            Files.move(other, file, StandardCopyOption.REPLACE_EXISTING);
            log.reopen();
            log.append(prepared(2));

            assertThat(log.scan()).hasSize(1);
        }
    }

    @Test
    void anAppendWhileReleasedOpensTheFileAgain(@TempDir Path dir) throws Exception {
        try (var log = new FileRecoveryLog(dir.resolve("tx.log"))) {
            log.append(prepared(1));
            log.release();

            log.append(prepared(2));

            assertThat(log.scan()).hasSize(2);
        }
    }

    @Test
    void releaseAndReopenAreIdempotent(@TempDir Path dir) throws Exception {
        try (var log = new FileRecoveryLog(dir.resolve("tx.log"))) {
            log.release();
            log.release();
            log.reopen();
            log.reopen();
            log.append(prepared(1));

            assertThat(log.scan()).hasSize(1);
        }
    }

    @Test
    void aClosedJournalStaysClosed(@TempDir Path dir) throws Exception {
        var log = new FileRecoveryLog(dir.resolve("tx.log"));
        log.close();

        assertThatThrownBy(log::reopen).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void theNoOpJournalHasNothingToRelease() {
        assertThatCode(() -> {
            NoOpRecoveryLog.INSTANCE.release();
            NoOpRecoveryLog.INSTANCE.reopen();
        }).doesNotThrowAnyException();
    }

    @Test
    void theManagerPassesThroughToItsJournal(@TempDir Path dir) throws Exception {
        try (var log = new FileRecoveryLog(dir.resolve("tx.log"))) {
            var manager = new MansartTransactionManager(log);
            manager.release();
            manager.reopen();
            log.append(prepared(1));

            assertThat(log.scan()).hasSize(1);
        }
        new MansartTransactionManager().release();
    }
}
