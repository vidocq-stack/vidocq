package io.vidocq.mansart.transactions.core;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double that records every {@link XAResource} call made by the transaction manager.
 * Used by {@code ResourceEnlistmentTest} and {@code TwoPhaseCommitTest} to assert protocol
 * conformance.
 */
final class RecordingXAResource implements XAResource {

    final String name;
    final List<String> log;
    /** Flag flipped on prepare to simulate a rollback vote. {@code XA_OK} by default. */
    int prepareReturn = XAResource.XA_OK;
    /** When true, prepare throws XAException(XA_RBROLLBACK). */
    boolean prepareThrowsRollback;
    /** When true, commit throws XAException(XAER_RMFAIL) — used to test heuristic paths later. */
    boolean commitThrows;

    RecordingXAResource(String name, List<String> log) {
        this.name = name;
        this.log = log;
    }

    @Override public void start(Xid xid, int flags) { log.add(name + ".start"); }
    @Override public void end(Xid xid, int flags)   { log.add(name + ".end(" + flagName(flags) + ")"); }
    @Override public int  prepare(Xid xid) throws XAException {
        log.add(name + ".prepare");
        if (prepareThrowsRollback) {
            throw new XAException(XAException.XA_RBROLLBACK);
        }
        return prepareReturn;
    }
    @Override public void commit(Xid xid, boolean onePhase) throws XAException {
        log.add(name + ".commit(onePhase=" + onePhase + ")");
        if (commitThrows) {
            throw new XAException(XAException.XAER_RMFAIL);
        }
    }
    @Override public void rollback(Xid xid)               { log.add(name + ".rollback"); }
    @Override public void forget(Xid xid)                 { log.add(name + ".forget"); }
    @Override public Xid[] recover(int flag)              { return new Xid[0]; }
    @Override public boolean isSameRM(XAResource other)   { return this == other; }
    @Override public int     getTransactionTimeout()      { return 0; }
    @Override public boolean setTransactionTimeout(int s) { return false; }

    private static String flagName(int f) {
        return switch (f) {
            case XAResource.TMSUCCESS  -> "TMSUCCESS";
            case XAResource.TMFAIL     -> "TMFAIL";
            case XAResource.TMSUSPEND  -> "TMSUSPEND";
            default                    -> "0x" + Integer.toHexString(f);
        };
    }
}
