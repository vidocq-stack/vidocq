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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation thread-safe du {@link HealthCheckRegistry}.
 *
 * <p>Stocke les checks dans une {@link ConcurrentHashMap} par ({@link ProbeType}, name).
 * Virtual-thread-friendly : aucun {@code synchronized}, aucun {@code ThreadLocal}.</p>
 *
 * <p>Pour {@link ProbeType#ALL}, {@link #getChecks(ProbeType)} retourne l'union de
 * LIVENESS, READINESS et STARTUP.</p>
 */
public final class KnockHealthCheckRegistry implements HealthCheckRegistry {

    /** Map<ProbeType, Map<name, HealthCheck>> — LIVENESS, READINESS, STARTUP uniquement. */
    private final ConcurrentHashMap<ProbeType, ConcurrentHashMap<String, HealthCheck>> checks =
            new ConcurrentHashMap<>();

    public KnockHealthCheckRegistry() {
        // Pré-initialiser les trois types concrets
        checks.put(ProbeType.LIVENESS, new ConcurrentHashMap<>());
        checks.put(ProbeType.READINESS, new ConcurrentHashMap<>());
        checks.put(ProbeType.STARTUP, new ConcurrentHashMap<>());
    }

    @Override
    public void register(ProbeType type, String name, HealthCheck check) {
        if (type == ProbeType.ALL) {
            throw new IllegalArgumentException(
                    "Cannot register a check with ProbeType.ALL — use LIVENESS, READINESS or STARTUP");
        }
        checks.get(type).put(name, check);
    }

    @Override
    public void unregister(String name) {
        // Parcourt LIVENESS, READINESS, STARTUP — retire le check si présent
        checks.values().forEach(map -> map.remove(name));
    }

    @Override
    public List<HealthCheck> getChecks(ProbeType type) {
        if (type == ProbeType.ALL) {
            List<HealthCheck> all = new ArrayList<>();
            all.addAll(checks.get(ProbeType.LIVENESS).values());
            all.addAll(checks.get(ProbeType.READINESS).values());
            all.addAll(checks.get(ProbeType.STARTUP).values());
            return List.copyOf(all);
        }
        return List.copyOf(checks.get(type).values());
    }
}
