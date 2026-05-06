package io.vidocq.mansart.transactions.it;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double that records every {@link XAResource} call. Same shape as the one in
 * {@code mansart-transactions-core/test} — duplicated here because it is package-private upstream.
 */
final class RecordingXAResource implements XAResource {

    final String name;
    final List<String> log;

    RecordingXAResource(String name, List<String> log) {
        this.name = name;
        this.log = log;
    }

    static RecordingXAResource named(String name) {
        return new RecordingXAResource(name, new ArrayList<>());
    }

    @Override public void start(Xid xid, int flags) { log.add(name + ".start"); }
    @Override public void end(Xid xid, int flags)   { log.add(name + ".end"); }
    @Override public int  prepare(Xid xid)          { log.add(name + ".prepare"); return XA_OK; }
    @Override public void commit(Xid xid, boolean onePhase) {
        log.add(name + ".commit(onePhase=" + onePhase + ")");
    }
    @Override public void rollback(Xid xid)              { log.add(name + ".rollback"); }
    @Override public void forget(Xid xid)                { /* no-op */ }
    @Override public Xid[] recover(int flag)             { return new Xid[0]; }
    @Override public boolean isSameRM(XAResource other)  { return this == other; }
    @Override public int     getTransactionTimeout()     { return 0; }
    @Override public boolean setTransactionTimeout(int s) { return false; }
}
