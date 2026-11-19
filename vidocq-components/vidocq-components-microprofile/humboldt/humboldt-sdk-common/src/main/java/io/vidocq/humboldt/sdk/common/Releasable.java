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
package io.vidocq.humboldt.sdk.common;

/**
 * A part of the SDK that holds connections, and can let them go and take them back while it stays the same object.
 *
 * <p>For a checkpoint of the process (CRaC): a checkpoint refuses a process with an open socket, and the OTLP/HTTP
 * exporters keep their connections to the collector in the pool of a JDK {@code HttpClient}. Whoever holds the
 * exporter keeps holding it across the checkpoint: only the connections go.</p>
 */
public interface Releasable {

    /**
     * Lets the connections go, once what was sent is answered. Until {@link #reopen()}, an export fails at once,
     * without connecting. Does nothing when already released.
     */
    void release();

    /** Takes the connections back after a {@link #release()}. Does nothing when not released. */
    void reopen();
}
