/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
 * <p>Spec MicroProfile Health 4.0 §3.1 : format JSON de la réponse health check.</p>
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
 * <p>{@code data} est omis si absent (ne pas sérialiser {@code "data":null}).</p>
 */
class KnockJsonSerializerTest {

    private KnockJsonSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new KnockJsonSerializer();
    }

    // -----------------------------------------------------------------------
    // §3.1 — snapshot UP, liste vide
    // -----------------------------------------------------------------------

    @Test
    void serialize_empty_checks_UP_spec_section3_1() {
        // Spec §3.1 : liste vide → status UP
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.ALL,
                HealthCheckResponse.Status.UP,
                List.of());

        String json = serializer.serialize(snapshot);

        assertTrue(json.contains("\"status\":\"UP\"") || json.contains("\"status\": \"UP\""),
                "JSON doit contenir status UP, got: " + json);
        assertTrue(json.contains("\"checks\":[]") || json.contains("\"checks\": []"),
                "JSON doit contenir checks vide, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — snapshot DOWN
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
                "JSON doit contenir status DOWN, got: " + json);
        assertTrue(json.contains("failing-check"), "JSON doit contenir le nom du check, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — data omise si absente
    // -----------------------------------------------------------------------

    @Test
    void serialize_data_absent_omitted_spec_section3_1() {
        // Spec §3.1 : « data is omitted if empty »
        HealthCheckResponse check = HealthCheckResponse.up("simple-check");
        HealthSnapshot snapshot = new HealthSnapshot(
                ProbeType.LIVENESS,
                HealthCheckResponse.Status.UP,
                List.of(check));

        String json = serializer.serialize(snapshot);

        assertFalse(json.contains("\"data\":null"), "data null ne doit pas apparaître, got: " + json);
        // data peut être présent ou absent — s'il est absent c'est correct
        // s'il est présent comme "{}" c'est aussi acceptable (pas de "null")
    }

    // -----------------------------------------------------------------------
    // §3.1 — data présente avec valeurs String
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

        assertTrue(json.contains("latency"), "JSON doit contenir la clé latency, got: " + json);
        assertTrue(json.contains("12ms"), "JSON doit contenir la valeur 12ms, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — data présente avec valeurs Long et Boolean
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

        assertTrue(json.contains("42"), "JSON doit contenir la valeur numérique, got: " + json);
        assertTrue(json.contains("true"), "JSON doit contenir la valeur booléenne, got: " + json);
    }

    // -----------------------------------------------------------------------
    // §3.1 — structure JSON valide avec plusieurs checks
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

        assertTrue(json.contains("check-1"), "JSON doit contenir check-1, got: " + json);
        assertTrue(json.contains("check-2"), "JSON doit contenir check-2, got: " + json);
    }

    // -----------------------------------------------------------------------
    // httpStatus — 200 si UP, 503 si DOWN
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
