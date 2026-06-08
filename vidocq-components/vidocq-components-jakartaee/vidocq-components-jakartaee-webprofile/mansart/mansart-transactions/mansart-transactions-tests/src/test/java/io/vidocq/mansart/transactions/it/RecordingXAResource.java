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
