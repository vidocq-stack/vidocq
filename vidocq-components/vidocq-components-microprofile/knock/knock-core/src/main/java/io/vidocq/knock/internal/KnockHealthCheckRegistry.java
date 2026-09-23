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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe implementation of {@link HealthCheckRegistry}.
 *
 * <p>Stores checks in a {@link ConcurrentHashMap} by ({@link ProbeType}, name).
 * Virtual-thread-friendly: no {@code synchronized}, no {@code ThreadLocal}.</p>
 *
 * <p>For {@link ProbeType#ALL}, {@link #getChecks(ProbeType)} returns the union of
 * LIVENESS, READINESS, and STARTUP.</p>
 *
 * <p>Keeps the last {@link CheckResult} of each check per probe in a second
 * {@link ConcurrentHashMap}, keyed like the checks: at most one entry per registered check per
 * probe, dropped when the check is unregistered.</p>
 */
public final class KnockHealthCheckRegistry implements HealthCheckRegistry {

    /** Map<ProbeType, Map<name, HealthCheck>> — LIVENESS, READINESS, STARTUP only. */
    private final ConcurrentHashMap<ProbeType, ConcurrentHashMap<String, HealthCheck>> checks =
            new ConcurrentHashMap<>();

    /** Map<ProbeType, Map<name, CheckResult>> — the last answer of each registered check. */
    private final ConcurrentHashMap<ProbeType, ConcurrentHashMap<String, CheckResult>> lastResults =
            new ConcurrentHashMap<>();

    public KnockHealthCheckRegistry() {
        // Pre-initialize the three concrete types
        checks.put(ProbeType.LIVENESS, new ConcurrentHashMap<>());
        checks.put(ProbeType.READINESS, new ConcurrentHashMap<>());
        checks.put(ProbeType.STARTUP, new ConcurrentHashMap<>());
        lastResults.put(ProbeType.LIVENESS, new ConcurrentHashMap<>());
        lastResults.put(ProbeType.READINESS, new ConcurrentHashMap<>());
        lastResults.put(ProbeType.STARTUP, new ConcurrentHashMap<>());
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
        // Iterate over LIVENESS, READINESS, STARTUP — remove the check if present
        checks.values().forEach(map -> map.remove(name));
        lastResults.values().forEach(map -> map.remove(name));
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

    @Override
    public Map<String, HealthCheck> getNamedChecks(ProbeType type) {
        if (type == ProbeType.ALL) {
            throw new IllegalArgumentException("ProbeType.ALL has no checks of its own — ask each probe type");
        }
        return Map.copyOf(checks.get(type));
    }

    @Override
    public void recordResult(CheckResult result) {
        ConcurrentHashMap<String, HealthCheck> registered = checks.get(result.probe());
        if (registered.containsKey(result.name())) {
            lastResults.get(result.probe()).put(result.name(), result);
        }
    }

    @Override
    public List<CheckResult> getLastResults() {
        List<CheckResult> all = new ArrayList<>();
        all.addAll(lastResults.get(ProbeType.LIVENESS).values());
        all.addAll(lastResults.get(ProbeType.READINESS).values());
        all.addAll(lastResults.get(ProbeType.STARTUP).values());
        return List.copyOf(all);
    }
}
