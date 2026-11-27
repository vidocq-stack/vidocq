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
package io.vidocq.chappe.grpc.client;

/**
 * The {@code grpc-timeout} request header: {@code TimeoutValue TimeoutUnit}, at most eight ASCII digits followed by
 * {@code n}, {@code u}, {@code m}, {@code S}, {@code M} or {@code H} (gRPC over HTTP/2, "Requests").
 */
final class GrpcTimeout {

    private static final long MAX_VALUE = 99_999_999L;

    private static final long[] UNIT_NANOS = {
        1L, 1_000L, 1_000_000L, 1_000_000_000L, 60_000_000_000L, 3_600_000_000_000L
    };
    private static final char[] UNITS = {'n', 'u', 'm', 'S', 'M', 'H'};

    private GrpcTimeout() {}

    /**
     * Encodes a remaining time in the finest unit that holds it in eight digits. A part of the unit is rounded up,
     * so the server never sees a shorter deadline than the client's; an expired deadline is one nanosecond. Any
     * {@code long} of nanoseconds fits: {@link Long#MAX_VALUE} is 2,562,048 hours.
     *
     * @param nanos the time left, in nanoseconds
     */
    static String encode(long nanos) {
        long remaining = Math.max(1L, nanos);
        int unit = 0;
        long value = remaining;
        while (value > MAX_VALUE) {
            unit++;
            value = ceilDiv(remaining, UNIT_NANOS[unit]);
        }
        return value + String.valueOf(UNITS[unit]);
    }

    private static long ceilDiv(long value, long divisor) {
        long quotient = value / divisor;
        return value % divisor == 0 ? quotient : quotient + 1;
    }
}
