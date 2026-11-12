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

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What keeps a transaction manager sound over time and across processes: transaction ids no other manager
 * produces (MANSART-060), a timeout that is applied (MANSART-061), a journal that stays small and holds no
 * orphan record (MANSART-062), synchronizations of the platform that run in their own order and on a
 * rollback-only transaction too (MANSART-063), and a one-phase commit that says what failed (MANSART-064).
 */
class TransactionRobustnessTest {

    private final List<String> log = new ArrayList<>();
    private MansartTransactionManager tm = new MansartTransactionManager();

    @TempDir
    Path dir;

    @AfterEach
    void cleanup() throws Exception {
        tm.setTransactionTimeout(0);
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            try { tm.rollback(); } catch (Exception _) { /* best effort */ }
        }
    }

    private Xid branchOfANewTransaction(MansartTransactionManager manager) throws Exception {
        var resource = new RecordingXAResource("r", new ArrayList<>());
        manager.begin();
        manager.getTransaction().enlistResource(resource);
        manager.rollback();
        return resource.startedWith;
    }

    /* ---- transaction ids ---- */

    @Test
    void twoManagersNeverProduceTheSameGlobalTransactionId() throws Exception {
        // Two managers stand for two processes, or one process before and after a restart.
        Xid first = branchOfANewTransaction(new MansartTransactionManager());
        Xid second = branchOfANewTransaction(new MansartTransactionManager());

        assertThat(first.getGlobalTransactionId()).isNotEqualTo(second.getGlobalTransactionId());
    }

    @Test
    void theNodeIdOpensTheGlobalTransactionId() throws Exception {
        Xid xid = branchOfANewTransaction(new MansartTransactionManager(NoOpRecoveryLog.INSTANCE, "node-a"));

        byte[] node = "node-a".getBytes(StandardCharsets.UTF_8);
        assertThat(Arrays.copyOf(xid.getGlobalTransactionId(), node.length)).isEqualTo(node);
        assertThat(xid.getGlobalTransactionId().length).isLessThanOrEqualTo(Xid.MAXGTRIDSIZE);
    }

    @Test
    void aNodeIdTooLongForAnXidIsRefused() {
        String tooLong = "n".repeat(MansartTransactionManager.MAX_NODE_ID_BYTES + 1);

        assertThatThrownBy(() -> new MansartTransactionManager(NoOpRecoveryLog.INSTANCE, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("node id");
    }

    /* ---- timeout ---- */

    @Test
    void aTransactionPastItsTimeoutIsRolledBackByItsCommit() throws Exception {
        tm.setTransactionTimeout(1);
        tm.begin();
        tm.getTransaction().enlistResource(new RecordingXAResource("r", log));

        letPass(Duration.ofMillis(1_400));

        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class).hasMessageContaining("timed out");
        assertThat(log).contains("r.timeout(1)", "r.rollback").doesNotContain("r.commit(onePhase=true)");
    }

    @Test
    void aTransactionEndedInTimeIsLeftAlone() throws Exception {
        tm.setTransactionTimeout(1);
        tm.begin();
        var tx = tm.getTransaction();
        tm.commit();

        letPass(Duration.ofMillis(1_400));

        assertThat(tx.getStatus()).isEqualTo(Status.STATUS_COMMITTED);
    }

    @Test
    void zeroPutsTheDefaultBackWhichIsNoTimeout() throws Exception {
        tm.setTransactionTimeout(1);
        tm.setTransactionTimeout(0);
        tm.begin();

        letPass(Duration.ofMillis(1_400));

        assertThatCode(tm::commit).doesNotThrowAnyException();
    }

    /** Waits out {@code time} without a sleep the thread could be woken from early. */
    private static void letPass(Duration time) {
        long deadline = System.nanoTime() + time.toNanos();
        for (long left = time.toNanos(); left > 0; left = deadline - System.nanoTime()) {
            LockSupport.parkNanos(left);
        }
    }

    @Test
    void theNodeIdComesFromItsSystemPropertyWhenSet() throws Exception {
        System.setProperty(MansartTransactionManager.NODE_ID_PROPERTY, "pod-7");
        try {
            Xid xid = branchOfANewTransaction(new MansartTransactionManager());

            assertThat(new String(xid.getGlobalTransactionId(), 0, 5, StandardCharsets.UTF_8)).isEqualTo("pod-7");
        } finally {
            System.clearProperty(MansartTransactionManager.NODE_ID_PROPERTY);
        }
    }

    /* ---- journal ---- */

    /** A journal that refuses the records of one type. */
    private static final class RefusingLog implements RecoveryLog {
        private final Type refused;
        RefusingLog(Type refused) { this.refused = refused; }
        @Override public void append(Record entry) throws IOException {
            if (entry.type() == refused) throw new IOException("disk full");
        }
        @Override public List<Record> scan() { return List.of(); }
        @Override public void close() { /* nothing */ }
    }

    private void beginWithTwoResources(RecoveryLog journal) throws Exception {
        tm = new MansartTransactionManager(journal);
        tm.begin();
        tm.getTransaction().enlistResource(new RecordingXAResource("a", log));
        tm.getTransaction().enlistResource(new RecordingXAResource("b", log));
    }

    @Test
    void aJournalThatRefusesThePreparedRecordRollsTheTransactionBack() throws Exception {
        beginWithTwoResources(new RefusingLog(RecoveryLog.Type.PREPARED));
        var tx = tm.getTransaction();

        assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class).hasCauseInstanceOf(IOException.class);
        assertThat(log).contains("a.rollback", "b.rollback").doesNotContain("a.commit(onePhase=false)");
        assertThat(tx.getStatus()).isEqualTo(Status.STATUS_ROLLEDBACK);
    }

    @Test
    void aJournalThatRefusesTheCommittingRecordLeavesTheOutcomeUnknown() throws Exception {
        beginWithTwoResources(new RefusingLog(RecoveryLog.Type.COMMITTING));
        var tx = tm.getTransaction();

        assertThatThrownBy(tm::commit).isInstanceOf(SystemException.class).hasCauseInstanceOf(IOException.class);
        assertThat(tx.getStatus()).isEqualTo(Status.STATUS_UNKNOWN);
    }

    @Test
    void aJournalThatRefusesTheCompletedRecordDoesNotFailACommittedTransaction() throws Exception {
        beginWithTwoResources(new RefusingLog(RecoveryLog.Type.COMPLETED));
        var tx = tm.getTransaction();

        assertThatCode(tm::commit).doesNotThrowAnyException();
        assertThat(tx.getStatus()).isEqualTo(Status.STATUS_COMMITTED);
    }

    @Test
    void theJournalIsCompactedAndKeepsWhatIsInDoubt() throws Exception {
        Path file = dir.resolve("tx.log");
        Xid stuck = new MansartXid(new byte[]{99}, new byte[]{0});
        try (var journal = new FileRecoveryLog(file, 8)) {
            journal.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, stuck));
            for (int i = 0; i < 40; i++) {
                Xid xid = new MansartXid(new byte[]{(byte) i}, new byte[]{0});
                journal.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, xid));
                journal.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, xid));
                journal.append(new RecoveryLog.Record(RecoveryLog.Type.COMPLETED, xid));
            }

            assertThat(Files.readAllLines(file)).hasSizeLessThan(30);
            assertThat(new MansartTransactionManager(journal).recover())
                    .extracting(r -> r.xid().getGlobalTransactionId()[0]).containsExactly((byte) 99);
        }
        try (var reopened = new FileRecoveryLog(file)) {
            assertThat(Files.readAllLines(file)).hasSize(1);
            assertThat(reopened.scan()).extracting(r -> r.xid().getGlobalTransactionId()[0])
                    .containsExactly((byte) 99);
        }
    }

    @Test
    void aTransactionRolledBackAfterItsPrepareLeavesNothingInDoubt() throws Exception {
        try (var journal = new FileRecoveryLog(dir.resolve("tx.log"))) {
            tm = new MansartTransactionManager(journal);
            tm.begin();
            tm.getTransaction().enlistResource(new RecordingXAResource("xa", log));
            tm.getTransaction().enlistResource(new FailingLastResource());

            assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class);
            assertThat(tm.recover()).isEmpty();
        }
    }

    /* ---- interposed synchronizations ---- */

    private Synchronization recording(String name) {
        return new Synchronization() {
            @Override public void beforeCompletion() { log.add(name + ".before"); }
            @Override public void afterCompletion(int status) { log.add(name + ".after"); }
        };
    }

    @Test
    void interposedSynchronizationsRunLastBeforeCompletionAndFirstAfter() throws Exception {
        tm.begin();
        tm.registerInterposedSynchronization(tm.getTransaction(), recording("platform"));
        tm.getTransaction().registerSynchronization(recording("app"));

        tm.commit();

        assertThat(log).containsExactly("app.before", "platform.before", "platform.after", "app.after");
    }

    @Test
    void anInterposedSynchronizationMayJoinARollbackOnlyTransaction() throws Exception {
        tm.begin();
        tm.setRollbackOnly();

        tm.registerInterposedSynchronization(tm.getTransaction(), recording("platform"));
        tm.rollback();

        assertThat(log).containsExactly("platform.after");
    }

    @Test
    void anInterposedSynchronizationIsRefusedOnceTheTransactionIsOver() throws Exception {
        tm.begin();
        var tx = tm.getTransaction();
        tm.commit();

        assertThatThrownBy(() -> tm.registerInterposedSynchronization(tx, recording("late")))
                .isInstanceOf(IllegalStateException.class);
    }

    /* ---- one-phase commit ---- */

    @Test
    void aOnePhaseCommitTheResourceRolledBackIsARollbackExceptionWithItsCause() throws Exception {
        var resource = new RecordingXAResource("r", log);
        resource.commitThrows = true;
        resource.commitErrorCode = XAException.XA_RBINTEGRITY;
        tm.begin();
        var tx = tm.getTransaction();
        tx.enlistResource(resource);

        assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class).hasCauseInstanceOf(XAException.class);
        assertThat(tx.getStatus()).isEqualTo(Status.STATUS_ROLLEDBACK);
    }

    @Test
    void aOnePhaseCommitOfUnknownOutcomeIsASystemExceptionWithItsCause() throws Exception {
        var resource = new RecordingXAResource("r", log);
        resource.commitThrows = true;
        tm.begin();
        tm.getTransaction().enlistResource(resource);

        assertThatThrownBy(tm::commit).isInstanceOf(SystemException.class).hasCauseInstanceOf(XAException.class);
    }

    /** A single-phase resource whose commit fails: the coordinator rolls the prepared branches back. */
    private static final class FailingLastResource implements SinglePhaseResource {
        @Override public void start(Xid xid, int flags) { /* nothing */ }
        @Override public void end(Xid xid, int flags) { /* nothing */ }
        @Override public int prepare(Xid xid) { return XA_OK; }
        @Override public void commit(Xid xid, boolean onePhase) throws XAException {
            throw new XAException(XAException.XA_RBROLLBACK);
        }
        @Override public void rollback(Xid xid) { /* nothing */ }
        @Override public void forget(Xid xid) { /* nothing */ }
        @Override public Xid[] recover(int flag) { return new Xid[0]; }
        @Override public boolean isSameRM(XAResource other) { return this == other; }
        @Override public int getTransactionTimeout() { return 0; }
        @Override public boolean setTransactionTimeout(int seconds) { return false; }
    }
}
