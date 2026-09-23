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
package io.vidocq.knock.spi;

import org.eclipse.microprofile.health.HealthCheck;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of Knock {@link HealthCheck}s.
 *
 * <p>Each check is associated with a {@link ProbeType} (LIVENESS, READINESS, STARTUP) and
 * identified by a unique name. The ALL aggregate exposes the full set of registered checks.</p>
 *
 * <p>Implementations must be thread-safe — the registry is accessed concurrently by the CDI
 * integration (registration) and the JAX-RS endpoints (reads). No {@code synchronized} or
 * {@code ThreadLocal} should be used — virtual-thread-friendly.</p>
 *
 * <p>The registry also remembers the last {@link CheckResult} of each check, one entry per
 * check per probe, as the probe requests recorded them. Reading the names or the results never
 * calls a check and never creates one: it is a read of what is already in memory.</p>
 */
public interface HealthCheckRegistry {

    /**
     * Registers a {@link HealthCheck} for the given {@link ProbeType}.
     *
     * <p>The name is derived from {@code check.getClass().getName()} and serves as the unique
     * key. A second registration with the same name replaces the previous one.</p>
     *
     * @param type  the probe type
     * @param name  the unique check name (e.g. CDI bean name or class)
     * @param check the check implementation
     */
    void register(ProbeType type, String name, HealthCheck check);

    /**
     * Removes the check identified by {@code name}.
     *
     * <p>Has no effect if no check with that name is registered.</p>
     *
     * @param name the unique check name to remove
     */
    void unregister(String name);

    /**
     * Returns all checks associated with the given {@link ProbeType}.
     *
     * <p>For {@link ProbeType#ALL}, returns the union of LIVENESS, READINESS, and STARTUP.</p>
     *
     * @param type the probe type
     * @return immutable list of checks registered for this type
     */
    List<HealthCheck> getChecks(ProbeType type);

    /**
     * Returns the checks registered for a concrete probe type, keyed by their registration name.
     *
     * <p>The default implementation derives the name from {@code check.getClass().getName()},
     * the naming used by the CDI integration.</p>
     *
     * @param type the probe type ({@link ProbeType#LIVENESS}, {@link ProbeType#READINESS} or
     *             {@link ProbeType#STARTUP})
     * @return immutable map of registration name to check
     * @throws IllegalArgumentException if {@code type} is {@link ProbeType#ALL}
     * @since 0.4.0
     */
    default Map<String, HealthCheck> getNamedChecks(ProbeType type) {
        if (type == ProbeType.ALL) {
            throw new IllegalArgumentException("ProbeType.ALL has no checks of its own — ask each probe type");
        }
        Map<String, HealthCheck> named = new LinkedHashMap<>();
        for (HealthCheck check : getChecks(type)) {
            named.putIfAbsent(check.getClass().getName(), check);
        }
        return Map.copyOf(named);
    }

    /**
     * Returns the names of the checks registered for the given probe type, without calling them.
     *
     * <p>For {@link ProbeType#ALL}, returns the union of LIVENESS, READINESS and STARTUP names.</p>
     *
     * @param type the probe type
     * @return immutable set of registration names
     * @since 0.4.0
     */
    default Set<String> getCheckNames(ProbeType type) {
        if (type != ProbeType.ALL) {
            return Set.copyOf(getNamedChecks(type).keySet());
        }
        Set<String> names = new LinkedHashSet<>();
        names.addAll(getNamedChecks(ProbeType.LIVENESS).keySet());
        names.addAll(getNamedChecks(ProbeType.READINESS).keySet());
        names.addAll(getNamedChecks(ProbeType.STARTUP).keySet());
        return Set.copyOf(names);
    }

    /**
     * Records the answer a check gave to a probe request, replacing the previous answer of the
     * same check for the same probe.
     *
     * <p>Called by the aggregation after it ran the check — never a reason to run it. A result
     * for a check that is not (or no longer) registered under {@code result.probe()} is ignored,
     * which bounds the memory to one entry per registered check per probe. The default
     * implementation keeps nothing.</p>
     *
     * @param result the observed result
     * @since 0.4.0
     */
    default void recordResult(CheckResult result) {
        // a registry that does not remember results
    }

    /**
     * Returns the last recorded result of each check, one per check per probe.
     *
     * <p>A check that no probe request has run yet has no entry. Reading never calls a check.
     * The default implementation remembers nothing and returns an empty list.</p>
     *
     * @return immutable list of the last results
     * @since 0.4.0
     */
    default List<CheckResult> getLastResults() {
        return List.of();
    }
}
