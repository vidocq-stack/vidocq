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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Thread-safe implementation of {@link HealthCheckRegistry}.
 *
 * <p>Stores the checks of each {@link ProbeType} in a {@link ConcurrentSkipListMap} keyed by
 * name. Virtual-thread-friendly: no {@code synchronized}, no {@code ThreadLocal}.</p>
 *
 * <p>Checks, names and results are listed probe by probe (LIVENESS, READINESS, STARTUP), each
 * probe in the order of the registration names, so {@code /health} lists the checks the same at
 * every start (BUG-20261114-01). The name order is used rather than the registration order: the
 * CDI integration registers the checks in the order its {@code Instance} lists them, which CDI
 * does not specify, while the name (the bean class) is the same at every start.</p>
 *
 * <p>For {@link ProbeType#ALL}, {@link #getChecks(ProbeType)} returns the union of
 * LIVENESS, READINESS, and STARTUP.</p>
 *
 * <p>Keeps the last {@link CheckResult} of each check per probe in a second set of maps, keyed
 * like the checks: at most one entry per registered check per probe, dropped when the check is
 * unregistered.</p>
 */
public final class KnockHealthCheckRegistry implements HealthCheckRegistry {

    /** Map<ProbeType, Map<name, HealthCheck>> — LIVENESS, READINESS, STARTUP only. */
    private final ConcurrentHashMap<ProbeType, ConcurrentSkipListMap<String, HealthCheck>> checks =
            new ConcurrentHashMap<>();

    /** Map<ProbeType, Map<name, CheckResult>> — the last answer of each registered check. */
    private final ConcurrentHashMap<ProbeType, ConcurrentSkipListMap<String, CheckResult>> lastResults =
            new ConcurrentHashMap<>();

    public KnockHealthCheckRegistry() {
        // Pre-initialize the three concrete types
        checks.put(ProbeType.LIVENESS, new ConcurrentSkipListMap<>());
        checks.put(ProbeType.READINESS, new ConcurrentSkipListMap<>());
        checks.put(ProbeType.STARTUP, new ConcurrentSkipListMap<>());
        lastResults.put(ProbeType.LIVENESS, new ConcurrentSkipListMap<>());
        lastResults.put(ProbeType.READINESS, new ConcurrentSkipListMap<>());
        lastResults.put(ProbeType.STARTUP, new ConcurrentSkipListMap<>());
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
        // a snapshot in name order; not Map.copyOf, whose order is salted per JVM (BUG-20261114-01)
        return Collections.unmodifiableMap(new LinkedHashMap<>(checks.get(type)));
    }

    @Override
    public void recordResult(CheckResult result) {
        ConcurrentSkipListMap<String, HealthCheck> registered = checks.get(result.probe());
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
