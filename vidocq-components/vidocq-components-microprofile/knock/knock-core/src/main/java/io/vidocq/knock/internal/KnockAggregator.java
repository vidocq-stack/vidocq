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
 * Exécute les {@link HealthCheck} enregistrés en parallèle et agrège les résultats.
 *
 * <p>Spec MicroProfile Health 4.0 §3.2 :</p>
 * <ul>
 *   <li>Le statut global est DOWN dès qu'au moins un check individuel est DOWN.</li>
 *   <li>Une liste de checks vide → statut global UP.</li>
 *   <li>Les exceptions levées par {@code call()} sont capturées et converties en check DOWN.</li>
 * </ul>
 *
 * <p>Exécution parallèle via {@code VirtualThreadPerTaskExecutor} — virtual-thread-friendly,
 * aucun thread platform fixe, aucun {@code synchronized}.</p>
 */
public final class KnockAggregator {

    /**
     * Agrège tous les checks du type donné depuis le registry.
     *
     * @param registry le registry source
     * @param type     le type de probe à agréger
     * @return un {@link HealthSnapshot} avec le statut global et les réponses individuelles
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
     * Appelle un check et capture toute exception — spec §3.2 : « exceptions treated as DOWN ».
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
