package io.vidocq.mansart.transactions.core;

import javax.transaction.xa.Xid;
import java.util.List;

/**
 * Outcome of a {@link MansartTransactionManager#recover(javax.transaction.xa.XAResource[])} run :
 * which Xids were auto-resolved (commit / rollback against the driver) and which remain in doubt
 * because no provided {@link javax.transaction.xa.XAResource} knew about them.
 *
 * <p>{@code committed} = the Mansart journal said COMMITTING and the driver still had the branch ;
 * we replayed the commit. {@code rolledBack} = the journal said only PREPARED ; we rolled back
 * because no durable commit decision exists. {@code stillInDoubt} = the journal mentioned a branch
 * that none of the supplied drivers know about → operator must investigate (driver was offline,
 * has its own forget rules, is on another machine, etc.).
 */
public record RecoveryReport(
        List<Xid> committed,
        List<Xid> rolledBack,
        List<RecoveryLog.Record> stillInDoubt) {
    public RecoveryReport {
        committed   = List.copyOf(committed);
        rolledBack  = List.copyOf(rolledBack);
        stillInDoubt = List.copyOf(stillInDoubt);
    }
}
