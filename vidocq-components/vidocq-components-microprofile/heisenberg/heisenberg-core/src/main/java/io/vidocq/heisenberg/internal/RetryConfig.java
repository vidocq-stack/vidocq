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

public record RetryConfig(
        int maxRetries,
        long delay,
        ChronoUnit delayUnit,
        long maxDuration,
        ChronoUnit durationUnit,
        long jitter,
        ChronoUnit jitterDelayUnit,
        Class<? extends Throwable>[] retryOn,
        Class<? extends Throwable>[] abortOn
) {
    public RetryConfig {
        // MP FT 4.1 §3.4: maxRetries = -1 means "retry indefinitely"
        // (infinite interpretation by RetryEngine). Any value < -1 is invalid.
        if (maxRetries < -1) {
            throw new IllegalArgumentException("maxRetries must be >= -1");
        }
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be >= 0");
        }
        if (maxDuration < 0) {
            throw new IllegalArgumentException("maxDuration must be >= 0");
        }
        if (jitter < 0) {
            throw new IllegalArgumentException("jitter must be >= 0");
        }
        retryOn = retryOn == null ? new Class[]{Exception.class} : retryOn.clone();
        abortOn = abortOn == null ? new Class[0] : abortOn.clone();
    }

    public Duration delayDuration() {
        return Duration.of(delay, delayUnit);
    }

    public Duration maxDurationValue() {
        return Duration.of(maxDuration, durationUnit);
    }

    public Duration jitterDuration() {
        return Duration.of(jitter, jitterDelayUnit);
    }
}
