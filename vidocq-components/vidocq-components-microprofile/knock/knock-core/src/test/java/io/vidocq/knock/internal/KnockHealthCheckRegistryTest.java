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
import io.vidocq.knock.spi.ProbeType;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — {@link KnockHealthCheckRegistry}: check names by probe and the last result of each
 * check, read without ever calling a check.
 */
class KnockHealthCheckRegistryTest {

    private static final Instant T1 = Instant.parse("2026-09-23T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-23T10:00:05Z");

    private KnockHealthCheckRegistry registry;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        registry = new KnockHealthCheckRegistry();
        calls = new AtomicInteger();
    }

    private HealthCheck counting(String name) {
        return () -> {
            calls.incrementAndGet();
            return HealthCheckResponse.up(name);
        };
    }

    private static CheckResult result(ProbeType probe, String name, HealthCheckResponse.Status status, Instant at) {
        return new CheckResult(probe, name, name, status, at, Map.of());
    }

    @Test
    void check_names_are_listed_per_probe_and_all_is_their_union() {
        registry.register(ProbeType.LIVENESS, "live", counting("live"));
        registry.register(ProbeType.READINESS, "ready", counting("ready"));
        registry.register(ProbeType.STARTUP, "started", counting("started"));

        assertEquals(Set.of("live"), registry.getCheckNames(ProbeType.LIVENESS));
        assertEquals(Set.of("ready"), registry.getCheckNames(ProbeType.READINESS));
        assertEquals(Set.of("started"), registry.getCheckNames(ProbeType.STARTUP));
        assertEquals(Set.of("live", "ready", "started"), registry.getCheckNames(ProbeType.ALL));
        assertEquals(0, calls.get(), "listing names must never call a check");
    }

    @Test
    void named_checks_of_a_probe_keep_their_registration_name() {
        HealthCheck live = counting("live");
        registry.register(ProbeType.LIVENESS, "com.acme.Live", live);

        assertEquals(Map.of("com.acme.Live", live), registry.getNamedChecks(ProbeType.LIVENESS));
        assertThrows(IllegalArgumentException.class, () -> registry.getNamedChecks(ProbeType.ALL));
    }

    @Test
    void no_result_before_any_probe_ran() {
        registry.register(ProbeType.LIVENESS, "live", counting("live"));

        assertTrue(registry.getLastResults().isEmpty());
        assertEquals(0, calls.get(), "reading results must never call a check");
    }

    @Test
    void a_new_result_replaces_the_previous_one_of_the_same_check_and_probe() {
        registry.register(ProbeType.LIVENESS, "live", counting("live"));
        registry.register(ProbeType.READINESS, "live", counting("live"));

        registry.recordResult(result(ProbeType.LIVENESS, "live", HealthCheckResponse.Status.UP, T1));
        registry.recordResult(result(ProbeType.LIVENESS, "live", HealthCheckResponse.Status.DOWN, T2));
        registry.recordResult(result(ProbeType.READINESS, "live", HealthCheckResponse.Status.UP, T2));

        List<CheckResult> results = registry.getLastResults();
        assertEquals(2, results.size(), "one entry per check per probe");
        CheckResult liveness = results.stream().filter(r -> r.probe() == ProbeType.LIVENESS).findFirst().orElseThrow();
        assertEquals(HealthCheckResponse.Status.DOWN, liveness.status());
        assertEquals(T2, liveness.observedAt());
    }

    @Test
    void a_result_for_a_check_not_registered_under_that_probe_is_ignored() {
        registry.register(ProbeType.LIVENESS, "live", counting("live"));

        registry.recordResult(result(ProbeType.READINESS, "live", HealthCheckResponse.Status.UP, T1));
        registry.recordResult(result(ProbeType.LIVENESS, "ghost", HealthCheckResponse.Status.UP, T1));

        assertTrue(registry.getLastResults().isEmpty(), "memory stays bounded by the registered checks");
    }

    @Test
    void unregister_forgets_the_results_of_the_check() {
        registry.register(ProbeType.LIVENESS, "live", counting("live"));
        registry.recordResult(result(ProbeType.LIVENESS, "live", HealthCheckResponse.Status.UP, T1));

        registry.unregister("live");

        assertTrue(registry.getLastResults().isEmpty());
    }
}
