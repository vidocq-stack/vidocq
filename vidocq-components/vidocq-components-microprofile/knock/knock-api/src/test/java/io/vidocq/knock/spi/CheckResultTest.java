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
package io.vidocq.knock.spi;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — {@link CheckResult}: the last answer of one registered check, kept as plain values.
 */
class CheckResultTest {

    private static final Instant AT = Instant.parse("2026-09-23T10:15:30Z");

    @Test
    void data_is_copied_and_immutable() {
        Map<String, String> data = new HashMap<>();
        data.put("pool", "8");

        CheckResult result = new CheckResult(ProbeType.READINESS, "com.acme.DbCheck", "db",
                HealthCheckResponse.Status.UP, AT, data);
        data.put("pool", "0");

        assertEquals(Map.of("pool", "8"), result.data());
        assertThrows(UnsupportedOperationException.class, () -> result.data().put("x", "y"));
    }

    @Test
    void null_data_becomes_an_empty_map() {
        CheckResult result = new CheckResult(ProbeType.LIVENESS, "c", "c",
                HealthCheckResponse.Status.DOWN, AT, null);

        assertEquals(Map.of(), result.data());
    }

    @Test
    void all_is_not_a_probe_a_check_answers_for() {
        assertThrows(IllegalArgumentException.class, () -> new CheckResult(ProbeType.ALL, "c", "c",
                HealthCheckResponse.Status.UP, AT, Map.of()));
    }

    @Test
    void identity_fields_are_mandatory() {
        assertThrows(NullPointerException.class, () -> new CheckResult(null, "c", "c",
                HealthCheckResponse.Status.UP, AT, Map.of()));
        assertThrows(NullPointerException.class, () -> new CheckResult(ProbeType.LIVENESS, null, "c",
                HealthCheckResponse.Status.UP, AT, Map.of()));
        assertThrows(NullPointerException.class, () -> new CheckResult(ProbeType.LIVENESS, "c", "c",
                null, AT, Map.of()));
        assertThrows(NullPointerException.class, () -> new CheckResult(ProbeType.LIVENESS, "c", "c",
                HealthCheckResponse.Status.UP, null, Map.of()));
    }
}
