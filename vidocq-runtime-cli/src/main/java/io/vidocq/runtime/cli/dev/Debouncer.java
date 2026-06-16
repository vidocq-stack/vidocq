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
package io.vidocq.runtime.cli.dev;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Collapses bursts of change events into a single action.
 *
 * <p>Filesystem watchers emit many events for one logical edit (editors write,
 * rename, touch). {@code Debouncer} accepts an event only when at least {@code window}
 * has elapsed since the previously accepted one, so a save that fires five events
 * triggers a single reload.
 *
 * <p>The clock is injectable, so the debounce behaviour is unit-tested deterministically
 * without sleeping.
 */
public final class Debouncer {

    private final long windowNanos;
    private final LongSupplier clockNanos;
    private final AtomicLong lastAccepted = new AtomicLong(Long.MIN_VALUE);

    public Debouncer(Duration window) {
        this(window, System::nanoTime);
    }

    public Debouncer(Duration window, LongSupplier clockNanos) {
        if (window.isNegative()) {
            throw new IllegalArgumentException("Debounce window must not be negative");
        }
        this.windowNanos = window.toNanos();
        this.clockNanos = clockNanos;
    }

    /**
     * @return {@code true} if this event should be acted on, {@code false} if it falls
     *         within the debounce window of the previously accepted event.
     */
    public boolean accept() {
        long now = clockNanos.getAsLong();
        long previous = lastAccepted.get();
        if (previous != Long.MIN_VALUE && now - previous < windowNanos) {
            return false;
        }
        lastAccepted.set(now);
        return true;
    }
}
