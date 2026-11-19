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
import jakarta.transaction.Transaction;
// XAResource was NOT migrated to the jakarta namespace by the Jakarta EE 9 rename — it stays
// in the JDK's javax.transaction.xa package (module java.transaction.xa). Eclipse Foundation
// kept it as-is because the JTA TCK references it at the JDK level.
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Single-thread, in-memory {@link Transaction}. Holds nothing but a status word for M1 — resource
 * enlistment, synchronisations and prepare/commit will land in M2/M4.
 *
 * <p>State machine accepted for M1 (subset of JTA §3.3.3) :
 * <pre>
 *   ACTIVE ──setRollbackOnly()──▶ MARKED_ROLLBACK
 *      │                                │
 *      │ commit()                       │ commit()
 *      ▼                                ▼
 *   COMMITTING                      ROLLING_BACK
 *      │                                │
 *      ▼                                ▼
 *   COMMITTED                       ROLLEDBACK
 *
 *   ACTIVE ──rollback()──▶ ROLLING_BACK ──▶ ROLLEDBACK
 *   MARKED_ROLLBACK ──rollback()──▶ ROLLING_BACK ──▶ ROLLEDBACK
 * </pre>
 *
 * <p>{@link #commit()} on {@code MARKED_ROLLBACK} performs the rollback then re-throws as a
 * {@link RollbackException} (per spec).
 */
final class MansartTransaction implements Transaction {

    private static final System.Logger LOG = System.getLogger("io.vidocq.mansart.transactions");

    /** Compare-and-set on {@link #status}: the timeout marks the transaction from another thread. */
    private static final VarHandle STATE;
    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(MansartTransaction.class, "status", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static byte[] longToBytes(long v) {
        return new byte[] {
                (byte)(v >>> 56), (byte)(v >>> 48), (byte)(v >>> 40), (byte)(v >>> 32),
                (byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8),  (byte) v };
    }

    private volatile int status = Status.STATUS_ACTIVE;
    private final List<Synchronization> syncs = new ArrayList<>();
    /** Synchronizations of the platform (TransactionSynchronizationRegistry): last before, first after. */
    private final List<Synchronization> interposed = new ArrayList<>();
    private final List<XAResource> resources = new ArrayList<>();
    /** Resources whose {@code end()} has already been called by an explicit
     *  {@link #delistResource(XAResource, int)} — they must not be re-ended at commit. */
    private final Set<XAResource> alreadyEnded =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final byte[] gtrid;
    /** Journal identity of the whole transaction (branch qualifier 0 is never handed to a resource). */
    private final Xid xid;
    /** One XA branch per enlisted resource — same gtrid, DISTINCT branch qualifier (XA spec). */
    private final IdentityHashMap<XAResource, Xid> branchXids = new IdentityHashMap<>();
    private int branchSequence;
    private final RecoveryLog recoveryLog;

    /** Seconds this transaction may stay active, {@code 0} for no limit. */
    private final int timeoutSeconds;
    private volatile boolean timedOut;
    /** The virtual thread that marks the transaction when its timeout expires; {@code null} without a timeout. */
    private Thread reaper;

    /**
     * @param gtrid          a global transaction id no other transaction manager produces (MANSART-060)
     * @param timeoutSeconds seconds the transaction may stay active, {@code 0} for no limit
     */
    MansartTransaction(RecoveryLog recoveryLog, byte[] gtrid, int timeoutSeconds) {
        this.recoveryLog = recoveryLog;
        this.gtrid = gtrid;
        this.xid = new MansartXid(gtrid, new byte[]{0});
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Starts the timeout, when there is one: a virtual thread that sleeps it out, then marks the transaction
     * rollback-only (MANSART-061). The thread that runs the transaction is not interrupted: its commit then rolls
     * back and throws {@link RollbackException}.
     */
    void armTimeout() {
        if (timeoutSeconds <= 0) return;
        reaper = Thread.ofVirtual().name("mansart-tx-timeout").start(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(timeoutSeconds));
                expire();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();   // the transaction ended in time
            }
        });
    }

    private void expire() {
        timedOut = true;
        if (STATE.compareAndSet(this, Status.STATUS_ACTIVE, Status.STATUS_MARKED_ROLLBACK)) {
            LOG.log(System.Logger.Level.WARNING, "Transaction {0} timed out after {1} s: marked rollback-only",
                    xid, timeoutSeconds);
        }
    }

    private RollbackException rolledBackAtCommit() {
        return new RollbackException(timedOut && timeoutSeconds > 0
                ? "Transaction timed out after " + timeoutSeconds + " s and was rolled back"
                : "Transaction was marked rollback-only");
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void commit() throws RollbackException, SystemException {
        if (status == Status.STATUS_MARKED_ROLLBACK) {
            doRollback();
            throw rolledBackAtCommit();
        }
        if (status != Status.STATUS_ACTIVE) {
            throw new IllegalStateException(
                    "commit() requires STATUS_ACTIVE, was " + statusName(status));
        }
        // beforeCompletion runs while we are still STATUS_ACTIVE so write-flushes can use the
        // resource as if user code were still executing. Any thrown exception triggers rollback.
        try {
            invokeBeforeCompletion();
        } catch (RuntimeException ex) {
            doRollback();
            RollbackException rex = new RollbackException(
                    "Synchronization.beforeCompletion failed — transaction rolled back");
            rex.initCause(ex);
            throw rex;
        }
        // A participant may mark the transaction rollback-only from beforeCompletion (a flush that
        // fails, typically): the mark is honoured, as it is when set before commit() (MANSART-051).
        // The same goes for the timeout, which marks from another thread: leaving ACTIVE is one
        // compare-and-set, so a transaction is either marked or on its way to commit (MANSART-061).
        int next = resources.size() > 1 ? Status.STATUS_PREPARING : Status.STATUS_COMMITTING;
        if (!STATE.compareAndSet(this, Status.STATUS_ACTIVE, next)) {
            doRollback();
            throw rolledBackAtCommit();
        }
        // End every resource that wasn't explicitly delisted before commit.
        endActiveResources(XAResource.TMSUCCESS);

        if (resources.isEmpty()) {
            status = Status.STATUS_COMMITTED;
            invokeAfterCompletion(Status.STATUS_COMMITTED);
        } else if (resources.size() == 1) {
            commitOnePhase(resources.get(0));
        } else {
            commitTwoPhase();
        }
    }

    /** 1PC degenerate path — a single resource: skip prepare entirely. */
    private void commitOnePhase(XAResource resource) throws RollbackException, SystemException {
        status = Status.STATUS_COMMITTING;
        try {
            resource.commit(xidFor(resource), true);
        } catch (XAException ex) {
            // XA_RB*: the resource rolled its branch back, the outcome is known (MANSART-064).
            if (ex.errorCode >= XAException.XA_RBBASE && ex.errorCode <= XAException.XA_RBEND) {
                status = Status.STATUS_ROLLEDBACK;
                invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
                RollbackException rex = new RollbackException(
                        "The resource rolled the transaction back at commit: " + ex.errorCode);
                rex.initCause(ex);
                throw rex;
            }
            status = Status.STATUS_UNKNOWN;
            invokeAfterCompletion(Status.STATUS_UNKNOWN);
            SystemException sys = new SystemException("XA single-resource commit failed: " + ex.errorCode);
            sys.initCause(ex);
            throw sys;
        }
        status = Status.STATUS_COMMITTED;
        invokeAfterCompletion(Status.STATUS_COMMITTED);
    }

    /** 2PC path : prepare every resource, abort if anyone votes rollback, then commit. */
    private void commitTwoPhase() throws RollbackException, SystemException {
        List<XAResource> prepared = prepareAll();
        status = Status.STATUS_PREPARED;

        // Durable record : every yes-voter is now in-doubt until COMPLETED is appended.
        // If we crash between here and the matching COMPLETED line, recover() will surface
        // these branches with type=PREPARED/COMMITTING and the operator can replay.
        try {
            recoveryLog.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED, xid));
        } catch (java.io.IOException ex) {
            // Nothing is committed yet: without a journal there is no safe commit, so roll back.
            status = Status.STATUS_ROLLING_BACK;
            rollbackQuietly(prepared);
            status = Status.STATUS_ROLLEDBACK;
            invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
            RollbackException rex = new RollbackException(
                    "The recovery log refused the PREPARED record — transaction rolled back");
            rex.initCause(ex);
            throw rex;
        }

        // LRCO — single-phase resources (JDBC local transactions wrapped as XAResource) cannot
        // really prepare: their commit IS the decision. Commit them first, while the real XA
        // resources are still merely prepared; if one fails, the prepared XA branches are rolled
        // back cleanly instead of being committed against a half-applied outcome. No COMMITTING
        // record has been written yet, so a crash in this window recovers as presumed-abort —
        // consistent with the rollback taken here.
        var lastResources = new ArrayList<XAResource>();
        var twoPhase = new ArrayList<XAResource>();
        for (XAResource r : prepared) {
            (r instanceof SinglePhaseResource ? lastResources : twoPhase).add(r);
        }
        commitLastResources(lastResources, twoPhase);

        status = Status.STATUS_COMMITTING;
        // Crossing the point of no return — write before any resource.commit() call so that a
        // crash during the loop still allows on-restart recovery to commit the rest.
        try {
            recoveryLog.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING, xid));
        } catch (java.io.IOException ex) {
            // A single-phase resource may have committed already: the outcome is not ours to state.
            status = Status.STATUS_UNKNOWN;
            invokeAfterCompletion(Status.STATUS_UNKNOWN);
            SystemException sys = new SystemException("Failed to append COMMITTING to the recovery log: " + ex);
            sys.initCause(ex);
            throw sys;
        }
        // The decision is commit: every branch gets its commit, whatever the others answer. A branch
        // left prepared would hold its locks until the next recovery scan (MANSART-055).
        XAException failure = null;
        for (XAResource r : twoPhase) {
            try {
                r.commit(xidFor(r), false);
            } catch (XAException ex) {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
            }
        }
        if (failure != null) {
            // Heuristic territory — surfaced as SystemException ; no COMPLETED record is written, so
            // the on-disk COMMITTING record lets recovery complete the failed branches on restart.
            status = Status.STATUS_UNKNOWN;
            invokeAfterCompletion(Status.STATUS_UNKNOWN);
            SystemException sys = new SystemException("XA two-phase commit failed: " + failure.errorCode);
            sys.initCause(failure);
            throw sys;
        }
        status = Status.STATUS_COMMITTED;
        appendCompleted();
        invokeAfterCompletion(Status.STATUS_COMMITTED);
    }

    /**
     * Prepares every resource and returns those that voted yes ({@code XA_RDONLY} ones are read-only and
     * forgotten: no commit needed); when one votes rollback, rolls the yes-voters back and throws.
     */
    private List<XAResource> prepareAll() throws RollbackException {
        status = Status.STATUS_PREPARING;
        var prepared = new ArrayList<XAResource>();
        boolean rollback = false;
        for (XAResource r : resources) {
            try {
                if (r.prepare(xidFor(r)) == XAResource.XA_OK) prepared.add(r);
            } catch (XAException _) {
                // The resource voted rollback — it has finalised its branch on its side.
                rollback = true;
            }
        }
        if (rollback) {
            // Roll back every resource that voted YES (they are still holding locks).
            status = Status.STATUS_ROLLING_BACK;
            rollbackQuietly(prepared);
            status = Status.STATUS_ROLLEDBACK;
            invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
            throw new RollbackException("At least one resource voted rollback during prepare");
        }
        return prepared;
    }

    /**
     * Commits the single-phase resources one by one; when one fails, rolls back the prepared two-phase branches and
     * the single-phase resources not committed yet, then throws.
     */
    private void commitLastResources(List<XAResource> lastResources, List<XAResource> twoPhase)
            throws RollbackException {
        for (int i = 0; i < lastResources.size(); i++) {
            try {
                lastResources.get(i).commit(xidFor(lastResources.get(i)), false);
            } catch (XAException ex) {
                status = Status.STATUS_ROLLING_BACK;
                rollbackQuietly(twoPhase);
                // Remaining single-phase resources have not committed yet — roll them back too.
                rollbackQuietly(lastResources.subList(i + 1, lastResources.size()));
                status = Status.STATUS_ROLLEDBACK;
                // The PREPARED record has its end: nothing stays in doubt (MANSART-062).
                appendCompleted();
                invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
                RollbackException rex = new RollbackException(
                        "Last-resource commit failed — transaction rolled back");
                rex.initCause(ex);
                throw rex;
            }
        }
    }

    private void rollbackQuietly(List<XAResource> branches) {
        for (XAResource r : branches) {
            try { r.rollback(xidFor(r)); } catch (XAException _) { /* best effort */ }
        }
    }

    /**
     * Closes the transaction's journal entry. The outcome is settled by then, so a journal that refuses the record
     * does not fail the transaction: the entry stays, and the next recovery scan finds no branch behind it.
     */
    private void appendCompleted() {
        try {
            recoveryLog.append(new RecoveryLog.Record(RecoveryLog.Type.COMPLETED, xid));
        } catch (java.io.IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "The recovery log refused the COMPLETED record of " + xid, ex);
        }
    }

    @Override
    public void rollback() throws SystemException {
        if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
            throw new IllegalStateException(
                    "rollback() requires STATUS_ACTIVE or STATUS_MARKED_ROLLBACK, was "
                            + statusName(status));
        }
        doRollback();
    }

    private void doRollback() {
        status = Status.STATUS_ROLLING_BACK;
        endActiveResources(XAResource.TMFAIL);
        for (XAResource r : resources) {
            try { r.rollback(xidFor(r)); } catch (XAException _) { /* best effort */ }
        }
        status = Status.STATUS_ROLLEDBACK;
        invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
    }

    /** The branch Xid this resource was started with — per-branch XA calls must reuse it. */
    private Xid xidFor(XAResource r) {
        Xid branch = branchXids.get(r);
        return branch != null ? branch : xid;
    }

    private void endActiveResources(int flag) {
        for (XAResource r : resources) {
            if (alreadyEnded.contains(r)) continue;
            try {
                r.end(xidFor(r), flag);
                alreadyEnded.add(r);
            } catch (XAException _) {
                // Best effort — proceed even if end() fails ; commit/rollback is what matters.
            }
        }
    }

    /**
     * By index, not by iterator: a participant may register another one from {@code beforeCompletion} (a flush
     * that takes its connection then), and the late one gets its own {@code beforeCompletion} (MANSART-052).
     * The interposed synchronizations run once the others are done (Jakarta Transactions, section 4.2 of the
     * TransactionSynchronizationRegistry contract).
     */
    private void invokeBeforeCompletion() {
        int next = 0;
        int nextInterposed = 0;
        while (next < syncs.size() || nextInterposed < interposed.size()) {
            if (next < syncs.size()) {
                syncs.get(next++).beforeCompletion();
            } else {
                interposed.get(nextInterposed++).beforeCompletion();
            }
        }
    }

    /**
     * After-completion callbacks run when the transaction is finalised — by spec they should
     * never propagate an exception. We swallow each one so that one failing sync can't stop the
     * others from observing the outcome. The interposed synchronizations run first.
     */
    private void invokeAfterCompletion(int finalStatus) {
        if (reaper != null) reaper.interrupt();
        afterCompletion(interposed, finalStatus);
        afterCompletion(syncs, finalStatus);
    }

    private static void afterCompletion(List<Synchronization> participants, int finalStatus) {
        for (int i = 0; i < participants.size(); i++) {
            try {
                participants.get(i).afterCompletion(finalStatus);
            } catch (RuntimeException _) {
                // spec §3.3.5 — afterCompletion exceptions are swallowed
            }
        }
    }

    /**
     * Registers a synchronization of the platform: it may join a transaction marked rollback-only, where
     * {@link #registerSynchronization} refuses, since what it cleans up exists whatever the outcome (MANSART-063).
     */
    void registerInterposed(Synchronization sync) {
        if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
            throw new IllegalStateException(
                    "An interposed synchronization requires STATUS_ACTIVE or STATUS_MARKED_ROLLBACK, was "
                            + statusName(status));
        }
        interposed.add(sync);
    }

    @Override
    public void setRollbackOnly() {
        // Marking twice is no error: each @Transactional frame an exception crosses marks (MANSART-053).
        if (STATE.compareAndSet(this, Status.STATUS_ACTIVE, Status.STATUS_MARKED_ROLLBACK)
                || status == Status.STATUS_MARKED_ROLLBACK) {
            return;
        }
        throw new IllegalStateException(
                "setRollbackOnly() requires STATUS_ACTIVE or STATUS_MARKED_ROLLBACK, was " + statusName(status));
    }

    @Override
    public boolean enlistResource(XAResource xaRes) throws RollbackException, SystemException {
        if (status == Status.STATUS_MARKED_ROLLBACK) {
            throw new RollbackException("Cannot enlist on a marked-rollback transaction");
        }
        if (status != Status.STATUS_ACTIVE) {
            throw new IllegalStateException(
                    "enlistResource() requires STATUS_ACTIVE, was " + statusName(status));
        }
        Xid branchXid = new MansartXid(gtrid, longToBytes(++branchSequence));
        try {
            // Before start(): XA applies a timeout to the branches started after it is set.
            if (timeoutSeconds > 0) xaRes.setTransactionTimeout(timeoutSeconds);
            xaRes.start(branchXid, XAResource.TMNOFLAGS);
        } catch (XAException ex) {
            throw new SystemException("XAResource.start failed: " + ex.errorCode);
        }
        branchXids.put(xaRes, branchXid);
        resources.add(xaRes);
        return true;
    }

    @Override
    public boolean delistResource(XAResource xaRes, int flag) throws SystemException {
        if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
            throw new IllegalStateException(
                    "delistResource() requires STATUS_ACTIVE or STATUS_MARKED_ROLLBACK, was "
                            + statusName(status));
        }
        if (!resources.contains(xaRes)) return false;
        try {
            xaRes.end(xidFor(xaRes), flag);
            alreadyEnded.add(xaRes);
        } catch (XAException ex) {
            throw new SystemException("XAResource.end failed: " + ex.errorCode);
        }
        return true;
    }

    @Override
    public void registerSynchronization(Synchronization sync) {
        if (status != Status.STATUS_ACTIVE) {
            throw new IllegalStateException(
                    "registerSynchronization() requires STATUS_ACTIVE, was " + statusName(status));
        }
        syncs.add(sync);
    }

    private static String statusName(int s) {
        return switch (s) {
            case Status.STATUS_ACTIVE          -> "STATUS_ACTIVE";
            case Status.STATUS_MARKED_ROLLBACK -> "STATUS_MARKED_ROLLBACK";
            case Status.STATUS_PREPARED        -> "STATUS_PREPARED";
            case Status.STATUS_COMMITTED       -> "STATUS_COMMITTED";
            case Status.STATUS_ROLLEDBACK      -> "STATUS_ROLLEDBACK";
            case Status.STATUS_UNKNOWN         -> "STATUS_UNKNOWN";
            case Status.STATUS_NO_TRANSACTION  -> "STATUS_NO_TRANSACTION";
            case Status.STATUS_PREPARING       -> "STATUS_PREPARING";
            case Status.STATUS_COMMITTING      -> "STATUS_COMMITTING";
            case Status.STATUS_ROLLING_BACK    -> "STATUS_ROLLING_BACK";
            default                            -> "UNKNOWN(" + s + ")";
        };
    }
}
