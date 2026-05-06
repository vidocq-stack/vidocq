package io.vidocq.mansart.transactions.core;

import javax.transaction.xa.Xid;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Append-only journal of two-phase-commit transitions, used to detect in-doubt transactions
 * after a crash. The TM writes one {@link Record} per state transition during a 2PC commit ;
 * on restart, {@link #scan()} replays the journal and {@link MansartTransactionManager#recover()}
 * surfaces the {@link Xid}s that need driver-side recovery.
 *
 * <p>Implementations :
 * <ul>
 *   <li>{@link NoOpRecoveryLog} — volatile, useful for tests and embedded use without durability.</li>
 *   <li>{@link FileRecoveryLog} — durable, append-only file with fsync after every write.</li>
 * </ul>
 *
 * <p>Recovery semantics :
 * <ul>
 *   <li>{@code PREPARED} without a matching {@code COMMITTED}/{@code ROLLEDBACK} → must rollback
 *       on restart (decision was never made).</li>
 *   <li>{@code COMMITTING} without a matching {@code COMPLETED} → must commit on restart
 *       (point-of-no-return crossed).</li>
 *   <li>{@code COMPLETED} entries can be garbage-collected.</li>
 * </ul>
 */
public interface RecoveryLog extends Closeable {

    /** Record kind. {@code COMMITTED} / {@code ROLLEDBACK} are aliases of {@code COMPLETED}
     *  with an outcome flag — kept as a single type to keep the file format minimal. */
    enum Type {
        /** Prepare phase has reported XA_OK on every resource — TX is in-doubt until commit/rollback. */
        PREPARED,
        /** Point of no return crossed — TX must commit on restart even if process died before {@code COMPLETED}. */
        COMMITTING,
        /** Outcome reached (commit or rollback). The matching transaction can be garbage-collected. */
        COMPLETED
    }

    record Record(Type type, Xid xid) { }

    /** Append a record and force it to durable storage before returning. */
    void append(Record record) throws IOException;

    /** Read every record in chronological order. Used at startup for recovery. */
    List<Record> scan() throws IOException;

    @Override
    void close() throws IOException;
}
