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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** The {@code grpc-timeout} value: at most eight digits, in the finest unit that holds the deadline. */
class GrpcTimeoutTest {

    @Test
    void the_finest_unit_that_fits_in_eight_digits_is_chosen() {
        assertEquals("1n", GrpcTimeout.encode(1));
        assertEquals("99999999n", GrpcTimeout.encode(99_999_999));
        assertEquals("100000u", GrpcTimeout.encode(100_000_000));
        assertEquals("1000000u", GrpcTimeout.encode(Duration.ofSeconds(1).toNanos()));
        assertEquals("100000m", GrpcTimeout.encode(Duration.ofSeconds(100).toNanos()));
        assertEquals("3600000m", GrpcTimeout.encode(Duration.ofHours(1).toNanos()));
        assertEquals("100000S", GrpcTimeout.encode(Duration.ofSeconds(100_000).toNanos()));
        assertEquals(
                "1666667M", GrpcTimeout.encode(Duration.ofSeconds(100_000_000).toNanos()));
    }

    @Test
    void a_part_of_the_unit_is_rounded_up_so_the_server_never_sees_a_shorter_deadline() {
        assertEquals("100001u", GrpcTimeout.encode(100_000_001));
    }

    @Test
    void the_longest_deadline_fits_in_hours() {
        assertEquals("2562048H", GrpcTimeout.encode(Long.MAX_VALUE));
    }

    @Test
    void an_expired_deadline_is_one_nanosecond() {
        assertEquals("1n", GrpcTimeout.encode(0));
        assertEquals("1n", GrpcTimeout.encode(-5));
    }
}
