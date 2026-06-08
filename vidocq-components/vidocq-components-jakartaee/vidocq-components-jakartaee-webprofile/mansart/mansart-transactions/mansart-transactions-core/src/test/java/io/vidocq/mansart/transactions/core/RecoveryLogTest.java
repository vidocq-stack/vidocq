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

import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 — durable recovery log : append/scan round-trip, journal entries written during 2PC commit,
 * in-doubt detection via {@link MansartTransactionManager#recover()}.
 */
class RecoveryLogTest {

    @Test
    void appendThenScanReadsBackInOrder(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid xid1 = new MansartXid(new byte[]{1}, new byte[]{0});
        Xid xid2 = new MansartXid(new byte[]{2}, new byte[]{0});

        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, xid1));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, xid1));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMPLETED, xid1));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, xid2));
        }

        try (var log = new FileRecoveryLog(file)) {
            var records = log.scan();
            assertThat(records).hasSize(4);
            assertThat(records.get(0).type()).isEqualTo(RecoveryLog.Type.PREPARED);
            assertThat(records.get(1).type()).isEqualTo(RecoveryLog.Type.COMMITTING);
            assertThat(records.get(2).type()).isEqualTo(RecoveryLog.Type.COMPLETED);
            assertThat(records.get(3).type()).isEqualTo(RecoveryLog.Type.PREPARED);
        }
    }

    @Test
    void noOpLogScansEmpty() throws Exception {
        var log = NoOpRecoveryLog.INSTANCE;
        log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, new MansartXid(new byte[]{1}, new byte[]{0})));
        assertThat(log.scan()).isEmpty();
    }

    @Test
    void twoPhaseCommitWritesPreparedCommittingCompleted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        try (var log = new FileRecoveryLog(file)) {
            TransactionManager tm = new MansartTransactionManager(log);
            tm.begin();
            List<String> xaLog = new ArrayList<>();
            tm.getTransaction().enlistResource(new RecordingXAResource("a", xaLog));
            tm.getTransaction().enlistResource(new RecordingXAResource("b", xaLog));
            tm.commit();
        }

        try (var log = new FileRecoveryLog(file)) {
            var records = log.scan();
            assertThat(records).extracting(RecoveryLog.Record::type)
                    .containsExactly(
                            RecoveryLog.Type.PREPARED,
                            RecoveryLog.Type.COMMITTING,
                            RecoveryLog.Type.COMPLETED);
        }
    }

    @Test
    void singleResourceCommitDoesNotWriteAnything(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        try (var log = new FileRecoveryLog(file)) {
            TransactionManager tm = new MansartTransactionManager(log);
            tm.begin();
            tm.getTransaction().enlistResource(new RecordingXAResource("a", new ArrayList<>()));
            tm.commit();
        }

        try (var log = new FileRecoveryLog(file)) {
            // 1PC: no risk of heuristic split (single resource), no logging needed.
            assertThat(log.scan()).isEmpty();
        }
    }

    @Test
    void recoverReturnsInDoubtRecordsOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid done   = new MansartXid(new byte[]{1}, new byte[]{0});  // committed cleanly
        Xid stuck1 = new MansartXid(new byte[]{2}, new byte[]{0});  // crashed after PREPARED
        Xid stuck2 = new MansartXid(new byte[]{3}, new byte[]{0});  // crashed after COMMITTING

        // Pre-write a journal as if a crash had occurred mid-2PC.
        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED,   done));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, done));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMPLETED,  done));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED,   stuck1));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED,   stuck2));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, stuck2));
        }

        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            var inDoubt = tm.recover();

            assertThat(inDoubt).hasSize(2);
            // stuck1 : the last recorded state was PREPARED — driver-side rollback recommended.
            assertThat(inDoubt.get(0).type()).isEqualTo(RecoveryLog.Type.PREPARED);
            assertThat(inDoubt.get(0).xid().getGlobalTransactionId()).containsExactly((byte)2);
            // stuck2 : reached COMMITTING — driver-side commit needed to keep consistency.
            assertThat(inDoubt.get(1).type()).isEqualTo(RecoveryLog.Type.COMMITTING);
            assertThat(inDoubt.get(1).xid().getGlobalTransactionId()).containsExactly((byte)3);
        }
    }

    @Test
    void emptyLogYieldsNoRecovery(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            assertThat(tm.recover()).isEmpty();
        }
    }

    @Test
    void rollbackDoesNotWriteToLog(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        try (var log = new FileRecoveryLog(file)) {
            TransactionManager tm = new MansartTransactionManager(log);
            tm.begin();
            tm.getTransaction().enlistResource(new RecordingXAResource("a", new ArrayList<>()));
            tm.getTransaction().enlistResource(new RecordingXAResource("b", new ArrayList<>()));
            tm.rollback();
        }

        try (var log = new FileRecoveryLog(file)) {
            // Rollback path needs no journal — the resource branches are aborted unilaterally.
            assertThat(log.scan()).isEmpty();
        }
    }

    @Test
    void corruptedTailLineIsSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("tx.log");
        // Write a valid record then a truncated (corrupt) line as if a crash happened mid-write.
        java.nio.file.Files.writeString(file,
                "PREPARED|4D414E53|01|00\n" +
                "COMMITTING|4D414E53|01|0",  // missing newline + truncated
                java.nio.charset.StandardCharsets.UTF_8);

        try (var log = new FileRecoveryLog(file)) {
            var records = log.scan();
            // The first record is fine ; the truncated tail (odd-length hex in bqual) is
            // dropped silently — recovery continues with what it could read.
            assertThat(records).hasSize(1);
            assertThat(records.get(0).type()).isEqualTo(RecoveryLog.Type.PREPARED);
        }
    }
}
