package io.vidocq.mansart.transactions.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5b — drives the driver-side auto-recovery API
 * {@link MansartTransactionManager#recover(XAResource[])} :
 * crosses the Mansart journal with each {@link XAResource#recover(int)} response and resolves
 * each in-doubt branch (commit if COMMITTING, rollback if PREPARED).
 */
class RecoveryDriverSideTest {

    @Test
    void committingRecordTriggersDriverCommit(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid x = new MansartXid(new byte[]{1}, new byte[]{0});

        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED,   x));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, x));
        }

        var driver = new RecoverableXAResource("db", List.of(x));
        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            var report = tm.recover(new XAResource[]{driver});

            assertThat(report.committed()).hasSize(1);
            assertThat(report.rolledBack()).isEmpty();
            assertThat(report.stillInDoubt()).isEmpty();
            assertThat(driver.committed).hasSize(1);
            assertThat(driver.committed.get(0).getGlobalTransactionId()).containsExactly((byte) 1);
            assertThat(driver.rolledBack).isEmpty();
        }
    }

    @Test
    void preparedOnlyRecordTriggersDriverRollback(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid x = new MansartXid(new byte[]{2}, new byte[]{0});

        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, x));
        }

        var driver = new RecoverableXAResource("db", List.of(x));
        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            var report = tm.recover(new XAResource[]{driver});

            assertThat(report.rolledBack()).hasSize(1);
            assertThat(report.committed()).isEmpty();
            assertThat(driver.rolledBack).hasSize(1);
            assertThat(driver.committed).isEmpty();
        }
    }

    @Test
    void unmatchedRecordStaysInDoubt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid logged = new MansartXid(new byte[]{3}, new byte[]{0});
        Xid driverHas = new MansartXid(new byte[]{99}, new byte[]{0});  // not in our journal

        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, logged));
        }

        var driver = new RecoverableXAResource("db", List.of(driverHas));
        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            var report = tm.recover(new XAResource[]{driver});

            // Journal said COMMITTING but driver doesn't know about that Xid → can't auto-resolve.
            assertThat(report.committed()).isEmpty();
            assertThat(report.rolledBack()).isEmpty();
            assertThat(report.stillInDoubt()).hasSize(1);
            assertThat(report.stillInDoubt().get(0).type()).isEqualTo(RecoveryLog.Type.COMMITTING);
            assertThat(driver.committed).isEmpty();
            assertThat(driver.rolledBack).isEmpty();
        }
    }

    @Test
    void multipleResourcesAreScannedAndDispatched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid xA = new MansartXid(new byte[]{10}, new byte[]{0});
        Xid xB = new MansartXid(new byte[]{20}, new byte[]{0});

        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, xA));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED,   xB));
        }

        var driverA = new RecoverableXAResource("a", List.of(xA));
        var driverB = new RecoverableXAResource("b", List.of(xB));
        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            var report = tm.recover(new XAResource[]{driverA, driverB});

            assertThat(report.committed()).hasSize(1);
            assertThat(report.rolledBack()).hasSize(1);
            assertThat(report.stillInDoubt()).isEmpty();
            assertThat(driverA.committed).hasSize(1);
            assertThat(driverA.rolledBack).isEmpty();
            assertThat(driverB.committed).isEmpty();
            assertThat(driverB.rolledBack).hasSize(1);
        }
    }

    @Test
    void completedRecordsAreNotRevisited(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid done = new MansartXid(new byte[]{42}, new byte[]{0});

        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED,   done));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, done));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMPLETED,  done));
        }

        var driver = new RecoverableXAResource("db", List.of(done));
        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            var report = tm.recover(new XAResource[]{driver});

            assertThat(report.committed()).isEmpty();
            assertThat(report.rolledBack()).isEmpty();
            assertThat(driver.committed).isEmpty();
            assertThat(driver.rolledBack).isEmpty();
        }
    }

    @Test
    void noResourcesIsEquivalentToReadOnlyRecover(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tx.log");
        Xid x = new MansartXid(new byte[]{7}, new byte[]{0});
        try (var log = new FileRecoveryLog(file)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, x));
        }
        try (var log = new FileRecoveryLog(file)) {
            var tm = new MansartTransactionManager(log);
            var report = tm.recover(new XAResource[0]);
            // No resources → can't resolve anything. Surface everything as still-in-doubt.
            assertThat(report.stillInDoubt()).hasSize(1);
            assertThat(report.committed()).isEmpty();
            assertThat(report.rolledBack()).isEmpty();
        }
    }

    /** XAResource test double that returns a fixed list of Xids from {@link #recover(int)} and
     *  records every commit/rollback call it receives. */
    private static final class RecoverableXAResource implements XAResource {
        final String name;
        final List<Xid> inDoubt;
        final List<Xid> committed = new ArrayList<>();
        final List<Xid> rolledBack = new ArrayList<>();

        RecoverableXAResource(String name, List<Xid> inDoubt) {
            this.name = name;
            this.inDoubt = inDoubt;
        }

        @Override public Xid[] recover(int flag) {
            // CDI/JTA semantics : caller invokes once with TMSTARTRSCAN | TMENDRSCAN to get
            // everything in one shot.
            return inDoubt.toArray(new Xid[0]);
        }
        @Override public void commit(Xid xid, boolean onePhase) { committed.add(xid); }
        @Override public void rollback(Xid xid)                 { rolledBack.add(xid); }
        @Override public void start(Xid xid, int flags)         { /* unused in recovery */ }
        @Override public void end(Xid xid, int flags)           { /* unused */ }
        @Override public int  prepare(Xid xid)                  { return XA_OK; }
        @Override public void forget(Xid xid)                   { /* unused */ }
        @Override public boolean isSameRM(XAResource other)     { return this == other; }
        @Override public int     getTransactionTimeout()        { return 0; }
        @Override public boolean setTransactionTimeout(int s)   { return false; }
    }
}
