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
package io.vidocq.knock.internal;

import io.vidocq.knock.spi.ProbeType;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — {@link KnockJsonSerializer}.
 *
 * <p>MicroProfile Health 4.0 spec §3.1: JSON format for the health check response.</p>
 *
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "checks": [
 *     {
 *       "name": "my-check",
 *       "status": "UP",
 *       "data": { "key": "value" }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>HTTP 200 si {@code status=UP}, HTTP 503 si {@code status=DOWN}.</p>
 * <p>{@code data} is omitted when absent (do not serialize {@code "data":null}).</p>
 */
class KnockJsonSerializerTest {

    private KnockJsonSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new KnockJsonSerializer();
    }

    // -----------------------------------------------------------------------
    // §3.1 — UP snapshot, empty list
    // -----------------------------------------------------------------------

    @Test
    void serialize_empty_checks_UP_spec_section3_1() {
        // Spec §3.1: empty list -> status UP
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.ALL,
                HealthCheckResponse.Status.UP,
                List.of());

        String json = serializer.serialize(snapshot);

        assertTrue(json.contains("\"status\":\"UP\"") || json.contains("\"status\": \"UP\""),
                "JSON must contain status UP, got: " + json);
        assertTrue(json.contains("\"checks\":[]") || json.contains("\"checks\": []"),
                "JSON must contain an empty checks array, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — DOWN snapshot
    // -----------------------------------------------------------------------

    @Test
    void serialize_snapshot_DOWN_spec_section3_1() {
        HealthCheckResponse check = HealthCheckResponse.down("failing-check");
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.LIVENESS,
                HealthCheckResponse.Status.DOWN,
                List.of(check));

        String json = serializer.serialize(snapshot);

        assertTrue(json.contains("\"status\":\"DOWN\"") || json.contains("\"status\": \"DOWN\""),
                "JSON must contain status DOWN, got: " + json);
        assertTrue(json.contains("failing-check"), "JSON must contain the check name, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — data omitted when absent
    // -----------------------------------------------------------------------

    @Test
    void serialize_data_absent_omitted_spec_section3_1() {
        // Spec §3.1: "data is omitted if empty"
        HealthCheckResponse check = HealthCheckResponse.up("simple-check");
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.LIVENESS,
                HealthCheckResponse.Status.UP,
                List.of(check));

        String json = serializer.serialize(snapshot);

        assertFalse(json.contains("\"data\":null"), "data null must not appear, got: " + json);
        // data may be present or absent — if it is absent, that is correct
        // if it is present as "{}", that is also acceptable (no "null")
    }

    // -----------------------------------------------------------------------
    // §3.1 — data present with String values
    // -----------------------------------------------------------------------

    @Test
    void serialize_data_string_value_spec_section3_1() {
        HealthCheckResponse check = HealthCheckResponse.named("db-check")
                .up()
                .withData("latency", "12ms")
                .build();
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.READINESS,
                HealthCheckResponse.Status.UP,
                List.of(check));

        String json = serializer.serialize(snapshot);

        assertTrue(json.contains("latency"), "JSON must contain the latency key, got: " + json);
        assertTrue(json.contains("12ms"), "JSON must contain the 12ms value, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — data present with Long and Boolean values
    // -----------------------------------------------------------------------

    @Test
    void serialize_data_numeric_and_boolean_spec_section3_1() {
        HealthCheckResponse check = HealthCheckResponse.named("multi-check")
                .up()
                .withData("connections", 42L)
                .withData("pooled", true)
                .build();
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.READINESS,
                HealthCheckResponse.Status.UP,
                List.of(check));

        String json = serializer.serialize(snapshot);

        assertTrue(json.contains("42"), "JSON must contain the numeric value, got: " + json);
        assertTrue(json.contains("true"), "JSON must contain the boolean value, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — valid JSON structure with multiple checks
    // -----------------------------------------------------------------------

    @Test
    void serialize_multiple_checks_spec_section3_1() {
        HealthCheckResponse c1 = HealthCheckResponse.up("check-1");
        HealthCheckResponse c2 = HealthCheckResponse.up("check-2");
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.ALL,
                HealthCheckResponse.Status.UP,
                List.of(c1, c2));

        String json = serializer.serialize(snapshot);

        assertTrue(json.contains("check-1"), "JSON must contain check-1, got: " + json);
        assertTrue(json.contains("check-2"), "JSON must contain check-2, got: " + json);
    }

    // -----------------------------------------------------------------------
    // httpStatus — 200 if UP, 503 if DOWN
    // -----------------------------------------------------------------------

    @Test
    void httpStatus_UP_returns_200() {
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.ALL, HealthCheckResponse.Status.UP, List.of());
        assertEquals(200, serializer.httpStatus(snapshot));
    }

    @Test
    void httpStatus_DOWN_returns_503() {
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.ALL, HealthCheckResponse.Status.DOWN, List.of());
        assertEquals(503, serializer.httpStatus(snapshot));
    }
}
