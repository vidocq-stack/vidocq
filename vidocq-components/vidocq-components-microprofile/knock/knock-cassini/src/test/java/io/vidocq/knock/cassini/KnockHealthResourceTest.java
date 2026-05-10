/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.cassini;

import io.vidocq.knock.runtime.HealthCheckRegistries;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — {@link KnockHealthResource}.
 *
 * <p>Spec MicroProfile Health 4.0 §3 :
 * <ul>
 *   <li>{@code GET /health}         — agrégat de tous les checks</li>
 *   <li>{@code GET /health/live}    — checks {@code @Liveness}</li>
 *   <li>{@code GET /health/ready}   — checks {@code @Readiness}</li>
 *   <li>{@code GET /health/started} — checks {@code @Startup}</li>
 * </ul>
 * HTTP 200 si statut UP, HTTP 503 si DOWN.</p>
 *
 * <p>Tests directs sur la ressource (pas de container HTTP) : on instancie la ressource
 * avec un {@link HealthCheckRegistry} fabriqué via la SPI runtime, et on vérifie la
 * {@link Response} JAX-RS produite.</p>
 */
class KnockHealthResourceTest {

    private HealthCheckRegistry registry;
    private KnockHealthResource resource;

    @BeforeAll
    static void installRuntimeDelegate() {
        // Frontière respectée : pas d'import de classe interne Cassini ; le delegate
        // de production sera fourni par Cassini en runtime, ici stub local de test.
        RuntimeDelegate.setInstance(new TestRuntimeDelegate());
    }

    @BeforeEach
    void setUp() {
        registry = HealthCheckRegistries.newRegistry();
        resource = new KnockHealthResource(registry);
    }

    // -----------------------------------------------------------------------
    // §3 — /health agrège tous les checks ; vide ⇒ UP / 200
    // -----------------------------------------------------------------------

    @Test
    void getHealth_empty_registry_returns_200_UP_spec_section3() {
        Response response = resource.getHealth();

        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        String body = (String) response.getEntity();
        assertTrue(body.contains("\"status\":\"UP\"") || body.contains("\"status\": \"UP\""),
                "body must contain status UP, got: " + body);
    }

    // -----------------------------------------------------------------------
    // §3.2 — un seul DOWN ⇒ statut global DOWN ⇒ 503
    // -----------------------------------------------------------------------

    @Test
    void getHealth_one_down_returns_503_DOWN_spec_section3_2() {
        registry.register(ProbeType.LIVENESS, "live-ok",
                () -> HealthCheckResponse.up("live-ok"));
        registry.register(ProbeType.READINESS, "ready-ko",
                () -> HealthCheckResponse.down("ready-ko"));

        Response response = resource.getHealth();

        assertEquals(503, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("DOWN"), "body must mention DOWN, got: " + body);
    }

    // -----------------------------------------------------------------------
    // §3 — /health/live ne voit que les @Liveness
    // -----------------------------------------------------------------------

    @Test
    void getLiveness_isolates_liveness_probes_spec_section3() {
        registry.register(ProbeType.LIVENESS,  "live-ok",  () -> HealthCheckResponse.up("live-ok"));
        registry.register(ProbeType.READINESS, "ready-ko", () -> HealthCheckResponse.down("ready-ko"));

        Response response = resource.getLiveness();

        // Readiness DOWN ne doit pas contaminer Liveness
        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("live-ok"));
        assertFalse(body.contains("ready-ko"),
                "body must not include readiness checks, got: " + body);
    }

    // -----------------------------------------------------------------------
    // §3 — /health/ready ne voit que les @Readiness
    // -----------------------------------------------------------------------

    @Test
    void getReadiness_isolates_readiness_probes_spec_section3() {
        registry.register(ProbeType.LIVENESS,  "live-ko",  () -> HealthCheckResponse.down("live-ko"));
        registry.register(ProbeType.READINESS, "ready-ok", () -> HealthCheckResponse.up("ready-ok"));

        Response response = resource.getReadiness();

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ready-ok"));
        assertFalse(body.contains("live-ko"));
    }

    // -----------------------------------------------------------------------
    // §3 — /health/started ne voit que les @Startup
    // -----------------------------------------------------------------------

    @Test
    void getStartup_isolates_startup_probes_spec_section3() {
        registry.register(ProbeType.STARTUP, "boot-ok", () -> HealthCheckResponse.up("boot-ok"));
        registry.register(ProbeType.LIVENESS, "live-ko", () -> HealthCheckResponse.down("live-ko"));

        Response response = resource.getStartup();

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("boot-ok"));
        assertFalse(body.contains("live-ko"));
    }

    // -----------------------------------------------------------------------
    // §3.2 — exception dans call() traitée comme DOWN ⇒ 503
    // -----------------------------------------------------------------------

    @Test
    void getHealth_exception_in_check_treated_as_DOWN_spec_section3_2() {
        HealthCheck explosive = () -> { throw new RuntimeException("boom"); };
        registry.register(ProbeType.LIVENESS, "explosive", explosive);

        Response response = resource.getHealth();

        assertEquals(503, response.getStatus());
    }

    // -----------------------------------------------------------------------
    // §3.1 — Content-Type application/json sur tous les endpoints
    // -----------------------------------------------------------------------

    @Test
    void all_endpoints_produce_application_json_spec_section3_1() {
        assertEquals(MediaType.APPLICATION_JSON, resource.getHealth().getMediaType().toString());
        assertEquals(MediaType.APPLICATION_JSON, resource.getLiveness().getMediaType().toString());
        assertEquals(MediaType.APPLICATION_JSON, resource.getReadiness().getMediaType().toString());
        assertEquals(MediaType.APPLICATION_JSON, resource.getStartup().getMediaType().toString());
    }
}

