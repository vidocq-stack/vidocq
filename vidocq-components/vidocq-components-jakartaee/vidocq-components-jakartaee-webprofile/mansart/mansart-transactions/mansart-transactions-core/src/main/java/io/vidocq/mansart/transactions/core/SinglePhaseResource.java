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

import javax.transaction.xa.XAResource;

/**
 * Marker for an enlisted resource that cannot really prepare — its {@code prepare} vote is a
 * formality ({@code XA_OK} without flushing) and its {@code commit} is the actual, irreversible
 * local commit. The JDBC local-transaction wrapper ({@code ConnectionXAResource} in
 * {@code mansart-transactions-jdbc}) is the canonical implementation.
 *
 * <p>{@link MansartTransaction} applies the last-resource-commit optimisation (LRCO) to these
 * resources in its two-phase path: after every resource has voted, single-phase resources are
 * committed <b>first</b> — their local commit is the de-facto decision point — and if one of
 * them fails, the real XA resources (still merely prepared) are rolled back cleanly instead of
 * being committed against a half-applied outcome. One single-phase resource per transaction is
 * therefore safe; with two or more, the window between their commits remains best-effort.
 */
public interface SinglePhaseResource extends XAResource {
}
