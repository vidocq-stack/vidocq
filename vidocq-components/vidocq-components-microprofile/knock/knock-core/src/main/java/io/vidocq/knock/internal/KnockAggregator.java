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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes registered {@link HealthCheck}s in parallel and aggregates the results.
 *
 * <p>MicroProfile Health 4.0 §3.2:</p>
 * <ul>
 *   <li>The global status is DOWN as soon as at least one individual check is DOWN.</li>
 *   <li>An empty check list → global status UP.</li>
 *   <li>Exceptions thrown by {@code call()} are caught and converted into a DOWN check.</li>
 * </ul>
 *
 * <p>Parallel execution via {@code VirtualThreadPerTaskExecutor} — virtual-thread-friendly,
 * no fixed platform threads, no {@code synchronized}.</p>
 */
public final class KnockAggregator {

    /** The concrete probe types, in the order {@link ProbeType#ALL} reports them. */
    private static final List<ProbeType> CONCRETE_TYPES =
            List.of(ProbeType.LIVENESS, ProbeType.READINESS, ProbeType.STARTUP);

    private final Clock clock;

    /** Creates an aggregator that timestamps results with the system UTC clock. */
    public KnockAggregator() {
        this(Clock.systemUTC());
    }

    /**
     * Creates an aggregator that timestamps results with the given clock.
     *
     * @param clock the clock giving {@link CheckResult#observedAt()} (non-null)
     */
    public KnockAggregator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Aggregates all checks of the given type from the registry.
     *
     * <p>Each answer is also handed to {@link HealthCheckRegistry#recordResult(CheckResult)},
     * under the probe the check is registered for — a {@link ProbeType#ALL} request records each
     * check under its own probe. Recording reuses the answer: it never calls a check again.</p>
     *
     * @param registry the source registry
     * @param type     the probe type to aggregate
     * @return a {@link HealthSnapshot} with the global status and individual responses
     */
    public HealthSnapshot aggregate(HealthCheckRegistry registry, ProbeType type) {
        List<ProbeType> types = type == ProbeType.ALL ? CONCRETE_TYPES : List.of(type);
        List<NamedCheck> healthChecks = new ArrayList<>();
        for (ProbeType probe : types) {
            registry.getNamedChecks(probe)
                    .forEach((name, check) -> healthChecks.add(new NamedCheck(probe, name, check)));
        }

        if (healthChecks.isEmpty()) {
            return new HealthSnapshot(type, HealthCheckResponse.Status.UP, List.of());
        }

        List<HealthCheckResponse> responses = executeParallel(healthChecks);
        Instant observedAt = clock.instant();
        for (int i = 0; i < responses.size(); i++) {
            registry.recordResult(toResult(healthChecks.get(i), responses.get(i), observedAt));
        }

        boolean anyDown = responses.stream()
                .anyMatch(r -> r.getStatus() == HealthCheckResponse.Status.DOWN);
        HealthCheckResponse.Status globalStatus = anyDown
                ? HealthCheckResponse.Status.DOWN
                : HealthCheckResponse.Status.UP;

        return new HealthSnapshot(type, globalStatus, responses);
    }

    private List<HealthCheckResponse> executeParallel(List<NamedCheck> checks) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HealthCheckResponse>> futures = new ArrayList<>(checks.size());
            for (NamedCheck named : checks) {
                futures.add(executor.submit(() -> safeCall(named.check())));
            }

            List<HealthCheckResponse> results = new ArrayList<>(futures.size());
            for (Future<HealthCheckResponse> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(downResponse(e));
                } catch (ExecutionException e) {
                    results.add(downResponse(e.getCause() != null ? e.getCause() : e));
                }
            }
            return results;
        }
    }

    /**
     * Turns an answer into plain values: the data values keep only their {@code String} form, so
     * the recorded result holds no application object.
     */
    private static CheckResult toResult(NamedCheck named, HealthCheckResponse response, Instant observedAt) {
        Map<String, String> data = new LinkedHashMap<>();
        response.getData().ifPresent(map -> map.forEach((key, value) -> data.put(key, String.valueOf(value))));
        HealthCheckResponse.Status status = response.getStatus() == null
                ? HealthCheckResponse.Status.DOWN
                : response.getStatus();
        return new CheckResult(named.probe(), named.name(), response.getName(), status, observedAt, data);
    }

    /** A check with the probe and name it is registered under. */
    private record NamedCheck(ProbeType probe, String name, HealthCheck check) {
    }

    /**
     * Calls a check and captures any exception — spec §3.2: "exceptions treated as DOWN".
     */
    private HealthCheckResponse safeCall(HealthCheck check) {
        try {
            return check.call();
        } catch (Exception e) {
            return downResponse(e);
        }
    }

    private HealthCheckResponse downResponse(Throwable cause) {
        String name = cause.getClass().getSimpleName();
        return new HealthCheckResponse(
                name,
                HealthCheckResponse.Status.DOWN,
                Optional.empty());
    }
}
