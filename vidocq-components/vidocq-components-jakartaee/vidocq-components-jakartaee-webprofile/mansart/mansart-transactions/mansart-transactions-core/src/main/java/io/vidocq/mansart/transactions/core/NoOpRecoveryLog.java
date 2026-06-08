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

import java.util.List;

/**
 * Default {@link RecoveryLog} — discards everything. Used when durability is not required (tests,
 * embedded apps that prefer "lose any in-doubt TX on crash" over the cost of fsync per commit).
 *
 * <p>{@link #scan()} always returns an empty list — there is nothing to recover when no journal
 * was kept.
 */
public final class NoOpRecoveryLog implements RecoveryLog {

    public static final NoOpRecoveryLog INSTANCE = new NoOpRecoveryLog();

    private NoOpRecoveryLog() {}

    @Override
    public void append(Record record) {
        // no-op
    }

    @Override
    public List<Record> scan() {
        return List.of();
    }

    @Override
    public void close() {
        // no-op
    }
}
