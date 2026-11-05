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
package io.vidocq.runtime.spi;

/**
 * Something that holds an operating-system resource, or freezes a fact of the process, which a
 * process snapshot (CRaC checkpoint, CRIU dump) cannot carry: a listening socket, a pool of
 * connections, an open file, an environment value read at start.
 *
 * <p>A {@link VidocqExtension} that needs it also implements this interface; the runtime hands
 * those extensions to {@link ExtensionContext#suspendables()}, in start order, with no reflection
 * and no dependency on any checkpoint technology. The module that drives the snapshot (for CRaC,
 * {@code vidocq-runtime-crac-extension}) calls {@link #suspend()} in the reverse of the start order, then
 * {@link #resume()} in the start order.
 *
 * <p>Both methods must be idempotent and quick, and must leave the state a plain
 * {@code onStart} would have produced once {@code resume()} returned.
 *
 * <p>The application takes part too, through the CDI events {@link io.vidocq.runtime.spi.event.Checkpoint} and
 * {@link io.vidocq.runtime.spi.event.Restore}, which the runtime fires from a suspendable of its own at
 * {@link #APPLICATION_PRIORITY}.
 */
public interface Suspendable {

    /**
     * The place of the application's own hooks among the extensions, as an extension {@link VidocqExtension#priority()
     * priority}: 5,000.
     *
     * <p>An extension below it (telemetry 100, pool 200, transactions 250) is suspended after the application's
     * {@link io.vidocq.runtime.spi.event.Checkpoint} observers and resumed before its
     * {@link io.vidocq.runtime.spi.event.Restore} observers, so they can still use it. An extension at or above it
     * (the HTTP server, 10,000) is suspended before them and resumed after them, so no request reaches the application
     * while it lets go.
     */
    int APPLICATION_PRIORITY = 5_000;

    /** Releases what a snapshot cannot carry. Called before the snapshot, in reverse start order. */
    void suspend();

    /** Takes back what {@link #suspend()} released. Called after the restore, in start order. */
    void resume();
}
