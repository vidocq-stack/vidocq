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
package io.vidocq.runtime.spi.event;

/**
 * Fired to the application's observers after a process was restored from a snapshot (CRaC restore), or after a
 * checkpoint failed. Observers take back what they let go of on {@link Checkpoint}.
 *
 * <pre>{@code
 * void afterRestore(@Observes Restore restore) {
 *     client = HttpClient.newHttpClient();
 *     catalogue.refreshIfOlderThan(Duration.ofHours(1));
 * }
 * }</pre>
 *
 * <p>The snapshot can be days old, and taken on another machine: what an observer read from the environment, the
 * clock or the network before the checkpoint may no longer hold. When it is fired, the pool, the transactions and
 * the telemetry exporters are open again, and the HTTP server does not take requests yet.
 *
 * @see Checkpoint
 * @see io.vidocq.runtime.spi.Suspendable#APPLICATION_PRIORITY
 */
public record Restore() {
}
