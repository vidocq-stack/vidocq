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

import io.vidocq.knock.spi.CheckResult;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — {@link KnockAggregator}.
 *
 * <p>Spec MicroProfile Health 4.0 §3.2 : « The overall status is DOWN if at least one
 * health check reports DOWN. An empty list of health checks results in a global UP status. »</p>
 */
class KnockAggregatorTest {

    private HealthCheckRegistry registry;
    private KnockAggregator aggregator;

    @BeforeEach
    void setUp() {
        registry = new KnockHealthCheckRegistry();
        aggregator = new KnockAggregator();
    }

    // -----------------------------------------------------------------------
    // §3.2 — liste vide → UP
    // -----------------------------------------------------------------------

    @Test
    void aggregate_empty_list_spec_section3_2_returns_UP() {
        // Spec §3.2 : « An empty list of health checks results in a global UP status. »
        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.LIVENESS);

        assertEquals(HealthCheckResponse.Status.UP, snapshot.status());
        assertTrue(snapshot.checks().isEmpty());
    }

    // -----------------------------------------------------------------------
    // §3.2 — tous UP → UP
    // -----------------------------------------------------------------------

    @Test
    void aggregate_all_up_spec_section3_2_returns_UP() {
        registry.register(ProbeType.LIVENESS, "check-1", () -> HealthCheckResponse.up("check-1"));
        registry.register(ProbeType.LIVENESS, "check-2", () -> HealthCheckResponse.up("check-2"));

        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.LIVENESS);

        assertEquals(HealthCheckResponse.Status.UP, snapshot.status());
        assertEquals(2, snapshot.checks().size());
    }

    // -----------------------------------------------------------------------
    // §3.2 — un seul DOWN → DOWN global
    // -----------------------------------------------------------------------

    @Test
    void aggregate_one_down_spec_section3_2_returns_DOWN() {
        // Spec §3.2 : « The overall status is DOWN if at least one health check reports DOWN. »
        registry.register(ProbeType.LIVENESS, "healthy", () -> HealthCheckResponse.up("healthy"));
        registry.register(ProbeType.LIVENESS, "failing", () -> HealthCheckResponse.down("failing"));

        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.LIVENESS);

        assertEquals(HealthCheckResponse.Status.DOWN, snapshot.status());
        assertEquals(2, snapshot.checks().size());
    }

    // -----------------------------------------------------------------------
    // §3.2 — exception dans call() → check DOWN
    // -----------------------------------------------------------------------

    @Test
    void aggregate_exception_in_call_spec_section3_2_treated_as_DOWN() {
        // Spec §3.2 : « Exceptions thrown by health checks are caught and treated as DOWN. »
        HealthCheck explosiveCheck = () -> {
            throw new RuntimeException("boom");
        };
        registry.register(ProbeType.LIVENESS, "explosive", explosiveCheck);

        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.LIVENESS);

        assertEquals(HealthCheckResponse.Status.DOWN, snapshot.status());
        assertEquals(1, snapshot.checks().size());
        assertEquals(HealthCheckResponse.Status.DOWN, snapshot.checks().getFirst().getStatus());
    }

    // -----------------------------------------------------------------------
    // §3 — ProbeType.ALL aggregates LIVENESS + READINESS + STARTUP
    // -----------------------------------------------------------------------

    @Test
    void aggregate_all_type_combines_all_probe_types() {
        registry.register(ProbeType.LIVENESS, "live-check", () -> HealthCheckResponse.up("live-check"));
        registry.register(ProbeType.READINESS, "ready-check", () -> HealthCheckResponse.up("ready-check"));
        registry.register(ProbeType.STARTUP, "startup-check", () -> HealthCheckResponse.up("startup-check"));

        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.ALL);

        assertEquals(HealthCheckResponse.Status.UP, snapshot.status());
        assertEquals(3, snapshot.checks().size());
    }

    @Test
    void aggregate_all_type_is_DOWN_if_any_check_in_any_type_is_DOWN() {
        registry.register(ProbeType.LIVENESS, "live", () -> HealthCheckResponse.up("live"));
        registry.register(ProbeType.READINESS, "ready", () -> HealthCheckResponse.down("ready"));

        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.ALL);

        assertEquals(HealthCheckResponse.Status.DOWN, snapshot.status());
    }

    // -----------------------------------------------------------------------
    // ProbeType isolation — LIVENESS does not aggregate READINESS
    // -----------------------------------------------------------------------

    @Test
    void aggregate_liveness_does_not_include_readiness_checks() {
        registry.register(ProbeType.LIVENESS, "live", () -> HealthCheckResponse.up("live"));
        registry.register(ProbeType.READINESS, "ready", () -> HealthCheckResponse.down("ready"));

        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.LIVENESS);

        // Readiness DOWN must not contaminate Liveness
        assertEquals(HealthCheckResponse.Status.UP, snapshot.status());
        assertEquals(1, snapshot.checks().size());
    }

    // -----------------------------------------------------------------------
    // unregister — removed check is no longer aggregated
    // -----------------------------------------------------------------------

    @Test
    void aggregate_after_unregister_check_is_removed() {
        registry.register(ProbeType.LIVENESS, "removable", () -> HealthCheckResponse.down("removable"));
        registry.unregister("removable");

        HealthSnapshot snapshot = aggregator.aggregate(registry, ProbeType.LIVENESS);

        assertEquals(HealthCheckResponse.Status.UP, snapshot.status());
        assertTrue(snapshot.checks().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Last results — remembered as the probe answered, without an extra call
    // -----------------------------------------------------------------------

    private static final Instant NOW = Instant.parse("2026-09-23T10:15:30Z");

    private static CheckResult only(HealthCheckRegistry registry, ProbeType probe, String name) {
        return registry.getLastResults().stream()
                .filter(r -> r.probe() == probe && r.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aggregate_records_the_last_result_of_each_check_with_its_time_and_data() {
        AtomicInteger calls = new AtomicInteger();
        registry.register(ProbeType.READINESS, "com.acme.DbCheck", () -> {
            calls.incrementAndGet();
            return HealthCheckResponse.named("db").up().withData("pool", 8).withData("ok", true).build();
        });
        KnockAggregator clocked = new KnockAggregator(Clock.fixed(NOW, ZoneOffset.UTC));

        clocked.aggregate(registry, ProbeType.READINESS);

        assertEquals(1, calls.get(), "exactly one call per check per probe request");
        CheckResult result = only(registry, ProbeType.READINESS, "com.acme.DbCheck");
        assertEquals("db", result.responseName());
        assertEquals(HealthCheckResponse.Status.UP, result.status());
        assertEquals(NOW, result.observedAt());
        assertEquals(Map.of("pool", "8", "ok", "true"), result.data());
        assertEquals(1, calls.get(), "reading the results never calls a check");
    }

    @Test
    void aggregate_all_records_each_check_under_its_own_probe() {
        registry.register(ProbeType.LIVENESS, "live", () -> HealthCheckResponse.up("live"));
        registry.register(ProbeType.STARTUP, "started", () -> HealthCheckResponse.down("started"));

        aggregator.aggregate(registry, ProbeType.ALL);

        assertEquals(HealthCheckResponse.Status.UP, only(registry, ProbeType.LIVENESS, "live").status());
        assertEquals(HealthCheckResponse.Status.DOWN, only(registry, ProbeType.STARTUP, "started").status());
        assertEquals(2, registry.getLastResults().size());
    }

    @Test
    void a_check_that_turns_down_shows_down_at_the_next_probe_request() {
        AtomicBoolean healthy = new AtomicBoolean(true);
        registry.register(ProbeType.LIVENESS, "flaky",
                () -> HealthCheckResponse.named("flaky").status(healthy.get()).build());

        aggregator.aggregate(registry, ProbeType.LIVENESS);
        assertEquals(HealthCheckResponse.Status.UP, only(registry, ProbeType.LIVENESS, "flaky").status());

        healthy.set(false);
        assertEquals(HealthCheckResponse.Status.UP, only(registry, ProbeType.LIVENESS, "flaky").status(),
                "nothing changes until a probe runs");
        aggregator.aggregate(registry, ProbeType.ALL);

        assertEquals(HealthCheckResponse.Status.DOWN, only(registry, ProbeType.LIVENESS, "flaky").status());
        assertEquals(1, registry.getLastResults().size());
    }

    @Test
    void an_exception_is_recorded_as_down_under_the_check_name() {
        registry.register(ProbeType.LIVENESS, "explosive", () -> {
            throw new IllegalStateException("boom");
        });

        aggregator.aggregate(registry, ProbeType.LIVENESS);

        CheckResult result = only(registry, ProbeType.LIVENESS, "explosive");
        assertEquals(HealthCheckResponse.Status.DOWN, result.status());
        assertEquals("IllegalStateException", result.responseName());
    }
}
