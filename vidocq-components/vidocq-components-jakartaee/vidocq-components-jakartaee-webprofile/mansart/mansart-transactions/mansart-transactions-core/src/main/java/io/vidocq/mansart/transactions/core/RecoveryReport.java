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
