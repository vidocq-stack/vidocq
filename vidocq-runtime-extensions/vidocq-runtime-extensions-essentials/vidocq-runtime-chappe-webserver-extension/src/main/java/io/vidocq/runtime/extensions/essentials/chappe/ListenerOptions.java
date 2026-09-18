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
package io.vidocq.runtime.extensions.essentials.chappe;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * How {@link ChappeServerBootstrap} starts a listener that an extension declared with
 * {@link ChappeMountPoint#declareListener(ChappeListener, ListenerOptions)}. A listener of the configuration
 * ({@code vidocq.chappe.listeners}) has the {@link #DEFAULTS}.
 *
 * <p>For a development tool, such as the dev console, that must never stop the application from starting:
 * <pre>{@code
 * mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 8888),
 *         new ListenerOptions(true, true, Duration.ofSeconds(1), this::bound));
 * }</pre>
 *
 * @param anyPortWhenTaken when the port is already in use, listen on a free port instead of failing the boot,
 *        and log a WARNING naming both ports; the port {@code 0} always binds a free port
 * @param quiet log the {@code Chappe listener '…' started on …} line at DEBUG instead of INFO, for an extension
 *        that prints its own
 * @param shutdownGracePeriod how long stopping the server waits for the requests in flight, or {@code null}
 *        for Chappe's own default (30 seconds); never negative
 * @param onBound called once the server listens, with the address it bound (its real port when the declared
 *        one was {@code 0} or was taken), or {@code null} for no call. It runs on the boot thread, after
 *        the mount point is frozen: it cannot mount anything. An exception it throws is logged as a WARNING
 *        and does not fail the boot.
 */
public record ListenerOptions(boolean anyPortWhenTaken, boolean quiet, Duration shutdownGracePeriod,
                              Consumer<InetSocketAddress> onBound) {

    /** The options of a listener of the configuration: a taken port fails the boot, logged at INFO. */
    public static final ListenerOptions DEFAULTS = new ListenerOptions(false, false, null, null);

    public ListenerOptions {
        if (shutdownGracePeriod != null && shutdownGracePeriod.isNegative()) {
            throw new IllegalArgumentException("negative shutdown grace period: " + shutdownGracePeriod);
        }
    }
}
