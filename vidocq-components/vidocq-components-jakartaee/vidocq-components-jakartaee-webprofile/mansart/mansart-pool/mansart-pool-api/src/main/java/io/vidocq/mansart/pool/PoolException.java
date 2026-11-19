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
package io.vidocq.mansart.pool;

import java.sql.SQLException;

/**
 * Raised by the pool itself, distinct from driver-originated {@link SQLException}s. The {@link #reason()}
 * is the structured cause; the message and underlying {@code cause} carry the diagnostic detail.
 *
 * <p>Extends {@link SQLException} so it flows transparently through code that catches the JDBC base
 * exception, while still being recognisable by pattern-matching on {@link #reason()} for fine-grained
 * handling (timeout vs connection refused vs pool shut down).
 */
public final class PoolException extends SQLException {

    public enum Reason {
        /** {@link PoolConfig#acquireTimeout()} elapsed waiting for a permit. */
        ACQUIRE_TIMEOUT,
        /** Idle connection failed validation (and revival was not possible). */
        VALIDATION_FAILED,
        /** Driver refused to open a fresh connection. */
        CONNECT_FAILED,
        /** {@code close()} called on the pool; further borrows are rejected. */
        POOL_CLOSED,
        /** Misuse of the API (e.g. {@link PoolConfig} contradiction caught at runtime). */
        MISCONFIGURED
    }

    private final Reason reason;

    public PoolException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PoolException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
