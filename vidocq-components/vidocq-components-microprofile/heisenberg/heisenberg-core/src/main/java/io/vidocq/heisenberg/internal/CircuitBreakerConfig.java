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
 * Immutable configuration for the {@code @CircuitBreaker} policy.
 *
 * <p>MP FT 4.1 §5: circuit breaker with a count-based sliding window.</p>
 *
 * @param requestVolumeThreshold minimum number of requests required to trigger ratio calculation (default 20)
 * @param failureRatio           failure-ratio threshold: failure_count / request_count (default 0.5)
 * @param delay                  delay before the OPEN → HALF_OPEN transition (default 5s)
 * @param delayUnit              delay unit (default SECONDS)
 * @param successThreshold       number of successes required in HALF_OPEN to close (default 1)
 * @param failOn                 exceptions that increment failures (default Throwable.class)
 * @param skipOn                 ignored exceptions (takes precedence over failOn)
 */
public record CircuitBreakerConfig(
        int requestVolumeThreshold,
        double failureRatio,
        long delay,
        ChronoUnit delayUnit,
        int successThreshold,
        Class<? extends Throwable>[] failOn,
        Class<? extends Throwable>[] skipOn
) {

    /** Default constants (MP FT 4.1 spec §5). */
    public static final CircuitBreakerConfig DEFAULT = new CircuitBreakerConfig(
            20, 0.5, 5, ChronoUnit.SECONDS, 1,
            new Class[]{Throwable.class}, new Class[0]
    );

    public CircuitBreakerConfig {
        if (requestVolumeThreshold <= 0) {
            throw new IllegalArgumentException("requestVolumeThreshold must be > 0");
        }
        if (failureRatio <= 0.0 || failureRatio > 1.0) {
            throw new IllegalArgumentException("failureRatio must be in (0, 1]");
        }
        if (delay <= 0) {
            throw new IllegalArgumentException("delay must be > 0");
        }
        if (successThreshold <= 0) {
            throw new IllegalArgumentException("successThreshold must be > 0");
        }
        failOn = failOn == null ? new Class[]{Throwable.class} : failOn.clone();
        skipOn = skipOn == null ? new Class[0] : skipOn.clone();
    }

    /** Converts the delay to {@link Duration}. */
    public Duration delayDuration() {
        return Duration.of(delay, delayUnit);
    }
}

