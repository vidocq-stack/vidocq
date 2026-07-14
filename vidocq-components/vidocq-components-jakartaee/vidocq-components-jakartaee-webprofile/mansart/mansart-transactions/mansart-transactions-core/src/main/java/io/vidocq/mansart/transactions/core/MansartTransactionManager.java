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

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Local-only Jakarta Transactions 2.0 {@link TransactionManager}.
 *
 * <p>M1 covers single-thread {@code begin/commit/rollback} only — synchronisations (M2),
 * suspend/resume (M3), resource enrolment + 2PC (M4) and recovery (M5) come in subsequent commits.
 *
 * <h3>Why {@link ThreadLocal} and not {@link java.lang.ScopedValue}</h3>
 * The Jakarta Transactions API is imperative : {@code begin()} returns and {@code commit()} comes
 * later from arbitrary call sites. {@link java.lang.ScopedValue} requires an enclosing
 * {@code run(...)} block, which would force every consumer to wrap its workload in a callback —
 * incompatible with the spec. {@link ThreadLocal} is correct here ; virtual threads inherit it
 * just like platform threads. M3 may add a {@code ScopedValue}-bound {@code runInTransaction(...)}
 * convenience helper on top of this, but the core is and stays imperative.
 */
public class MansartTransactionManager implements TransactionManager {

    private final ThreadLocal<MansartTransaction> active = new ThreadLocal<>();
    private final RecoveryLog recoveryLog;

    /** Default timeout in seconds applied to new transactions. {@code 0} = no timeout. M1 stores
     *  the value but does not enforce it yet — enforcement lands with M2's reaper task. */
    private volatile int defaultTimeoutSeconds;

    /** Volatile TM (no recovery log) — the most common case for embedded apps and tests. */
    public MansartTransactionManager() {
        this(NoOpRecoveryLog.INSTANCE);
    }

    /** Durable TM — pass a {@link FileRecoveryLog} (or any custom impl) to enable in-doubt
     *  transaction detection on restart. */
    public MansartTransactionManager(RecoveryLog recoveryLog) {
        this.recoveryLog = recoveryLog;
    }

    /**
     * True when this TM writes a durable recovery journal — a boot-time recovery scan
     * ({@link #recover(javax.transaction.xa.XAResource...)}) is then meaningful.
     */
    public boolean durable() {
        return recoveryLog != NoOpRecoveryLog.INSTANCE;
    }

    @Override
    public void begin() throws NotSupportedException {
        if (active.get() != null) {
            throw new NotSupportedException(
                    "A transaction is already active on this thread — nested transactions "
                            + "are not supported (use suspend()/resume() in M3+)");
        }
        active.set(new MansartTransaction(recoveryLog));
    }

    @Override
    public void commit() throws RollbackException, HeuristicMixedException,
            HeuristicRollbackException, SecurityException, SystemException {
        MansartTransaction tx = currentRequired();
        try {
            tx.commit();
        } finally {
            active.remove();
        }
    }

    @Override
    public void rollback() throws SecurityException, SystemException {
        MansartTransaction tx = currentRequired();
        try {
            tx.rollback();
        } finally {
            active.remove();
        }
    }

    @Override
    public int getStatus() {
        MansartTransaction tx = active.get();
        return tx == null ? Status.STATUS_NO_TRANSACTION : tx.getStatus();
    }

    @Override
    public Transaction getTransaction() {
        return active.get();
    }

    @Override
    public void setRollbackOnly() throws SystemException {
        currentRequired().setRollbackOnly();
    }

    @Override
    public void setTransactionTimeout(int seconds) throws SystemException {
        if (seconds < 0) {
            throw new SystemException("Negative transaction timeout: " + seconds);
        }
        this.defaultTimeoutSeconds = seconds;
    }

    @Override
    public void resume(Transaction tobj)
            throws InvalidTransactionException, IllegalStateException, SystemException {
        if (tobj == null) {
            // Spec is permissive — we treat null as "do nothing", symmetric with suspend()
            // returning null when no TX is bound.
            return;
        }
        if (active.get() != null) {
            throw new IllegalStateException(
                    "Cannot resume — a transaction is already active on this thread");
        }
        if (!(tobj instanceof MansartTransaction mtx)) {
            throw new InvalidTransactionException(
                    "Foreign Transaction implementation: " + tobj.getClass().getName());
        }
        active.set(mtx);
    }

    @Override
    public Transaction suspend() throws SystemException {
        MansartTransaction tx = active.get();
        if (tx == null) {
            return null;
        }
        active.remove();
        return tx;
    }

    private MansartTransaction currentRequired() {
        MansartTransaction tx = active.get();
        if (tx == null) {
            throw new IllegalStateException("No transaction active on this thread");
        }
        return tx;
    }

    /**
     * Replays the {@link RecoveryLog} and returns the records that are in-doubt — i.e. whose
     * most recent record for a given Xid is not {@code COMPLETED}.
     *
     * <p>Caller-side handling :
     * <ul>
     *   <li>{@link RecoveryLog.Type#PREPARED} → the resource voted yes but no commit decision
     *       was ever durably written ; safe to roll back the branch via the driver.</li>
     *   <li>{@link RecoveryLog.Type#COMMITTING} → point of no return was crossed ; the branch
     *       must be committed via the driver to restore consistency.</li>
     * </ul>
     *
     * <p>For M5 we surface the records and let the caller decide ; M5b will add a high-level
     * {@code recover(XAResource[])} that auto-resolves against driver-side {@code XAResource.recover()}
     * results.
     */
    public List<RecoveryLog.Record> recover() throws java.io.IOException {
        var lastByXid = new LinkedHashMap<XidKey, RecoveryLog.Record>();
        for (var rec : recoveryLog.scan()) {
            var key = XidKey.of(rec.xid());
            if (rec.type() == RecoveryLog.Type.COMPLETED) {
                lastByXid.remove(key);
            } else {
                lastByXid.put(key, rec);
            }
        }
        return List.copyOf(lastByXid.values());
    }

    /**
     * Drives the actual replay against {@link XAResource} drivers — M5b.
     *
     * <p>For every record returned by {@link #recover()} :
     * <ul>
     *   <li>look up the matching Xid in each driver's {@link XAResource#recover(int)} list ;</li>
     *   <li>if found AND the journal type is {@link RecoveryLog.Type#COMMITTING} → call
     *       {@link XAResource#commit(Xid, boolean) commit(xid, false)} (the durable decision is
     *       commit, the driver still holds the prepared branch — finish the job) ;</li>
     *   <li>if found AND the journal type is {@link RecoveryLog.Type#PREPARED} → call
     *       {@link XAResource#rollback(Xid) rollback(xid)} (no durable commit decision exists, so
     *       rolling back is the safe choice) ;</li>
     *   <li>if not found in any driver → leave the record in the {@link RecoveryReport#stillInDoubt}
     *       list so the operator can inspect it (driver may be offline / recycled / on another
     *       machine).</li>
     * </ul>
     *
     * <p>Idempotent : calling this twice with the same drivers re-resolves anything that the first
     * call left in doubt (driver-side state may have changed in the meantime).
     */
    public RecoveryReport recover(XAResource... resources) throws java.io.IOException {
        List<RecoveryLog.Record> inDoubt = recover();

        // Pre-compute every driver's in-doubt branches — one scan call per resource.
        var driverIndex = new LinkedHashMap<XAResource, java.util.Map<XidKey, Xid>>();
        for (XAResource r : resources) {
            var index = new LinkedHashMap<XidKey, Xid>();
            try {
                Xid[] xids = r.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
                if (xids != null) {
                    for (Xid x : xids) index.put(XidKey.of(x), x);
                }
            } catch (XAException ignored) {
                // Driver scan failed — treat as empty ; affected records stay in doubt.
            }
            driverIndex.put(r, index);
        }

        var committed   = new ArrayList<Xid>();
        var rolledBack  = new ArrayList<Xid>();
        var unresolved  = new ArrayList<RecoveryLog.Record>();

        for (RecoveryLog.Record rec : inDoubt) {
            var key = XidKey.of(rec.xid());
            XAResource match = null;
            Xid driverXid = null;
            for (var entry : driverIndex.entrySet()) {
                Xid x = entry.getValue().get(key);
                if (x != null) { match = entry.getKey(); driverXid = x; break; }
            }
            if (match == null) {
                unresolved.add(rec);
                continue;
            }
            try {
                if (rec.type() == RecoveryLog.Type.COMMITTING) {
                    match.commit(driverXid, false);
                    committed.add(driverXid);
                } else {
                    // PREPARED — no durable commit decision was made.
                    match.rollback(driverXid);
                    rolledBack.add(driverXid);
                }
            } catch (XAException ex) {
                // Driver rejected our resolution attempt — surface as still-in-doubt for now.
                unresolved.add(rec);
            }
        }
        return new RecoveryReport(committed, rolledBack, unresolved);
    }

    /** Equality wrapper for {@link javax.transaction.xa.Xid} — by-content hash/equals on
     *  format/gtrid/bqual. Required because Xid is an interface with no contract for equals. */
    private record XidKey(int format, java.util.List<Byte> gtrid, java.util.List<Byte> bqual) {
        static XidKey of(javax.transaction.xa.Xid x) {
            var g = x.getGlobalTransactionId();
            var b = x.getBranchQualifier();
            var gl = new java.util.ArrayList<Byte>(g.length);
            for (byte v : g) gl.add(v);
            var bl = new java.util.ArrayList<Byte>(b.length);
            for (byte v : b) bl.add(v);
            return new XidKey(x.getFormatId(), gl, bl);
        }
    }
}
