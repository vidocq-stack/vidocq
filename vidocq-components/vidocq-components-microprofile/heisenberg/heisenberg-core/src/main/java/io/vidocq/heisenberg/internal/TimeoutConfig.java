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
 * Immutable configuration for the {@code @Timeout} policy.
 *
 * <p>MP FT 4.1 §4: the timeout applies to each individual attempt.</p>
 *
 * @param value timeout duration (must be &gt; 0)
 * @param unit  ChronoUnit unit (spec default: MILLIS)
 */
public record TimeoutConfig(long value, ChronoUnit unit) {

    /** Spec default value: 1,000 ms. */
    public static final TimeoutConfig DEFAULT = new TimeoutConfig(1_000L, ChronoUnit.MILLIS);

    public TimeoutConfig {
        if (value <= 0) {
            throw new IllegalArgumentException("timeout value must be > 0, got: " + value);
        }
    }

    /** Converts the configuration to {@link Duration}. */
    public Duration duration() {
        return Duration.of(value, unit);
    }
}

