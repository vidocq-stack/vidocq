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
package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Immutable configuration for the {@code @Bulkhead} policy.
 *
 * <p>MP FT 4.1 §7: synchronous and asynchronous bulkhead.</p>
 *
 * @param value              maximum capacity (default 10)
 * @param waitingTaskQueue   async-mode wait queue size (default 10)
 */
public record BulkheadConfig(
        int value,
        int waitingTaskQueue
) {

    /** Default constants (MP FT 4.1 spec §7). */
    public static final BulkheadConfig DEFAULT = new BulkheadConfig(10, 10);

    public BulkheadConfig {
        if (value <= 0) {
            throw new IllegalArgumentException("value (max concurrent) must be > 0");
        }
        if (waitingTaskQueue < 0) {
            throw new IllegalArgumentException("waitingTaskQueue must be >= 0");
        }
    }
}

