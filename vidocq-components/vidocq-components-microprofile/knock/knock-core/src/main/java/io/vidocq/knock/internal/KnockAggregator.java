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

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Aggregates all checks of the given type from the registry.
     *
     * @param registry the source registry
     * @param type     the probe type to aggregate
     * @return a {@link HealthSnapshot} with the global status and individual responses
     */
    public HealthSnapshot aggregate(HealthCheckRegistry registry, ProbeType type) {
        List<HealthCheck> healthChecks = registry.getChecks(type);

        if (healthChecks.isEmpty()) {
            return new HealthSnapshot(type, HealthCheckResponse.Status.UP, List.of());
        }

        List<HealthCheckResponse> responses = executeParallel(healthChecks);

        boolean anyDown = responses.stream()
                .anyMatch(r -> r.getStatus() == HealthCheckResponse.Status.DOWN);
        HealthCheckResponse.Status globalStatus = anyDown
                ? HealthCheckResponse.Status.DOWN
                : HealthCheckResponse.Status.UP;

        return new HealthSnapshot(type, globalStatus, responses);
    }

    private List<HealthCheckResponse> executeParallel(List<HealthCheck> checks) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HealthCheckResponse>> futures = new ArrayList<>(checks.size());
            for (HealthCheck check : checks) {
                futures.add(executor.submit(() -> safeCall(check)));
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
