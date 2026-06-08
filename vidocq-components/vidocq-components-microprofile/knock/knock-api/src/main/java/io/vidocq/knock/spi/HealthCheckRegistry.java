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

import java.util.List;

/**
 * Registry of Knock {@link HealthCheck}s.
 *
 * <p>Each check is associated with a {@link ProbeType} (LIVENESS, READINESS, STARTUP) and
 * identified by a unique name. The ALL aggregate exposes the full set of registered checks.</p>
 *
 * <p>Implementations must be thread-safe — the registry is accessed concurrently by the CDI
 * integration (registration) and the JAX-RS endpoints (reads). No {@code synchronized} or
 * {@code ThreadLocal} should be used — virtual-thread-friendly.</p>
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
}
