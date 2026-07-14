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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final AtomicLong XID_COUNTER = new AtomicLong();
    private static byte[] longToBytes(long v) {
        return new byte[] {
                (byte)(v >>> 56), (byte)(v >>> 48), (byte)(v >>> 40), (byte)(v >>> 32),
                (byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8),  (byte) v };
    }

    private volatile int status = Status.STATUS_ACTIVE;
    private final List<Synchronization> syncs = new ArrayList<>();
    private final List<XAResource> resources = new ArrayList<>();
    /** Resources whose {@code end()} has already been called by an explicit
     *  {@link #delistResource(XAResource, int)} — they must not be re-ended at commit. */
    private final Set<XAResource> alreadyEnded =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Xid xid = new MansartXid(longToBytes(XID_COUNTER.incrementAndGet()), new byte[]{0});
    private final RecoveryLog recoveryLog;

    MansartTransaction() {
        this(NoOpRecoveryLog.INSTANCE);
    }

    MansartTransaction(RecoveryLog recoveryLog) {
        this.recoveryLog = recoveryLog;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void commit() throws RollbackException, SystemException {
        if (status == Status.STATUS_MARKED_ROLLBACK) {
            doRollback();
            throw new RollbackException("Transaction was marked rollback-only");
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
        // End every resource that wasn't explicitly delisted before commit.
        endActiveResources(XAResource.TMSUCCESS);

        if (resources.isEmpty()) {
            status = Status.STATUS_COMMITTING;
            status = Status.STATUS_COMMITTED;
            invokeAfterCompletion(Status.STATUS_COMMITTED);
            return;
        }
        if (resources.size() == 1) {
            // 1PC degenerate path — skip prepare entirely.
            status = Status.STATUS_COMMITTING;
            try {
                resources.get(0).commit(xid, true);
            } catch (XAException ex) {
                status = Status.STATUS_UNKNOWN;
                invokeAfterCompletion(Status.STATUS_UNKNOWN);
                throw new SystemException("XA single-resource commit failed: " + ex.errorCode);
            }
            status = Status.STATUS_COMMITTED;
            invokeAfterCompletion(Status.STATUS_COMMITTED);
            return;
        }

        // 2PC path : prepare every resource, abort if anyone votes rollback, then commit.
        status = Status.STATUS_PREPARING;
        var prepared = new ArrayList<XAResource>();
        boolean rollback = false;
        for (XAResource r : resources) {
            try {
                int vote = r.prepare(xid);
                if (vote == XAResource.XA_OK) {
                    prepared.add(r);
                }
                // XA_RDONLY : resource is read-only and can be forgotten — no commit needed.
            } catch (XAException ex) {
                rollback = true;
                // The resource voted rollback — it has finalised its branch on its side.
            }
        }
        if (rollback) {
            // Roll back every resource that voted YES (they are still holding locks).
            status = Status.STATUS_ROLLING_BACK;
            for (XAResource r : prepared) {
                try { r.rollback(xid); } catch (XAException ignored) { /* best effort */ }
            }
            status = Status.STATUS_ROLLEDBACK;
            invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
            throw new RollbackException("At least one resource voted rollback during prepare");
        }
        status = Status.STATUS_PREPARED;

        // Durable record : every yes-voter is now in-doubt until COMPLETED is appended.
        // If we crash between here and the matching COMPLETED line, recover() will surface
        // these branches with type=PREPARED/COMMITTING and the operator can replay.
        appendOrFailSystem(RecoveryLog.Type.PREPARED);

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
        for (int i = 0; i < lastResources.size(); i++) {
            try {
                lastResources.get(i).commit(xid, false);
            } catch (XAException ex) {
                status = Status.STATUS_ROLLING_BACK;
                for (XAResource r : twoPhase) {
                    try { r.rollback(xid); } catch (XAException ignored) { /* best effort */ }
                }
                // Remaining single-phase resources have not committed yet — roll them back too.
                for (XAResource r : lastResources.subList(i + 1, lastResources.size())) {
                    try { r.rollback(xid); } catch (XAException ignored) { /* best effort */ }
                }
                status = Status.STATUS_ROLLEDBACK;
                invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
                RollbackException rex = new RollbackException(
                        "Last-resource commit failed — transaction rolled back");
                rex.initCause(ex);
                throw rex;
            }
        }

        status = Status.STATUS_COMMITTING;
        // Crossing the point of no return — write before any resource.commit() call so that a
        // crash during the loop still allows on-restart recovery to commit the rest.
        appendOrFailSystem(RecoveryLog.Type.COMMITTING);
        for (XAResource r : twoPhase) {
            try {
                r.commit(xid, false);
            } catch (XAException ex) {
                // Heuristic territory — for M5 we surface as SystemException ; the on-disk
                // COMMITTING record means recovery can complete this commit on restart.
                status = Status.STATUS_UNKNOWN;
                invokeAfterCompletion(Status.STATUS_UNKNOWN);
                throw new SystemException("XA two-phase commit failed: " + ex.errorCode);
            }
        }
        status = Status.STATUS_COMMITTED;
        appendOrFailSystem(RecoveryLog.Type.COMPLETED);
        invokeAfterCompletion(Status.STATUS_COMMITTED);
    }

    private void appendOrFailSystem(RecoveryLog.Type type) throws SystemException {
        try {
            recoveryLog.append(new RecoveryLog.Record(type, xid));
        } catch (java.io.IOException ex) {
            // A failed write means the on-disk view is now inconsistent with reality — escalate.
            // The volatile NoOpRecoveryLog never throws, so this only fires for FileRecoveryLog.
            status = Status.STATUS_UNKNOWN;
            throw new SystemException("Failed to append " + type + " to recovery log: " + ex);
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
            try { r.rollback(xid); } catch (XAException ignored) { /* best effort */ }
        }
        status = Status.STATUS_ROLLEDBACK;
        invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
    }

    private void endActiveResources(int flag) {
        for (XAResource r : resources) {
            if (alreadyEnded.contains(r)) continue;
            try {
                r.end(xid, flag);
                alreadyEnded.add(r);
            } catch (XAException ignored) {
                // Best effort — proceed even if end() fails ; commit/rollback is what matters.
            }
        }
    }

    private void invokeBeforeCompletion() {
        for (Synchronization s : syncs) {
            s.beforeCompletion();
        }
    }

    /**
     * After-completion callbacks run when the transaction is finalised — by spec they should
     * never propagate an exception. We swallow each one so that one failing sync can't stop the
     * others from observing the outcome.
     */
    private void invokeAfterCompletion(int finalStatus) {
        for (Synchronization s : syncs) {
            try {
                s.afterCompletion(finalStatus);
            } catch (RuntimeException ignored) {
                // spec §3.3.5 — afterCompletion exceptions are swallowed
            }
        }
    }

    @Override
    public void setRollbackOnly() {
        if (status != Status.STATUS_ACTIVE) {
            throw new IllegalStateException(
                    "setRollbackOnly() requires STATUS_ACTIVE, was " + statusName(status));
        }
        status = Status.STATUS_MARKED_ROLLBACK;
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
        try {
            xaRes.start(xid, XAResource.TMNOFLAGS);
        } catch (XAException ex) {
            throw new SystemException("XAResource.start failed: " + ex.errorCode);
        }
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
            xaRes.end(xid, flag);
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
