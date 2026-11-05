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
 * Fired to the application's observers before a process snapshot (CRaC checkpoint) is taken, like CDI's
 * {@code Shutdown}, but the application keeps running once the snapshot is written or restored.
 *
 * <p>An observer lets go of what the snapshot cannot carry and the runtime does not know about: an open file, a
 * socket outside the runtime's own server, pool and exporters (a JDK {@code HttpClient}, a gRPC channel, a JDBC
 * connection of its own), a thread it started. It is also the last moment to load into memory what the restored
 * process should find there without loading it again.
 *
 * <pre>{@code
 * void beforeCheckpoint(@Observes @Priority(100) Checkpoint checkpoint) {
 *     catalogue.loadIfAbsent();
 *     client.close();
 * }
 * }</pre>
 *
 * <p>Observers run in the order of their {@code jakarta.annotation.Priority}, on the thread that drives the
 * snapshot. When it is fired, the HTTP server already stopped taking requests, and the pool, the transactions and
 * the telemetry exporters are still open. An observer that throws makes the checkpoint fail; the runtime then takes
 * back what it let go of, as after a restore.
 *
 * @see Restore
 * @see io.vidocq.runtime.spi.Suspendable#APPLICATION_PRIORITY
 */
public record Checkpoint() {
}
