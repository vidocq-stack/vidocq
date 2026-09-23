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

import org.eclipse.microprofile.health.HealthCheckResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The last answer of one registered check, as a probe request observed it.
 *
 * <p>Plain values only — no reference to the check, its bean or its class — so that a
 * tool (a dev console, a log) can hold and read it without keeping application objects
 * alive and without ever calling the check again. The data values of the
 * {@link HealthCheckResponse} are kept as their {@code String} form.</p>
 *
 * @param probe        the probe the check is registered under (never {@link ProbeType#ALL}:
 *                     a {@code /health} request records each check under its own probe)
 * @param name         the name the check is registered under in the {@link HealthCheckRegistry}
 * @param responseName the name carried by the response ({@code HealthCheckResponse#getName()});
 *                     may be {@code null} if the check answered without one
 * @param status       the status the check answered
 * @param observedAt   when the answer was received
 * @param data         the response data, values as strings (empty when the response had none)
 */
public record CheckResult(
        ProbeType probe,
        String name,
        String responseName,
        HealthCheckResponse.Status status,
        Instant observedAt,
        Map<String, String> data) {

    public CheckResult {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        if (probe == ProbeType.ALL) {
            throw new IllegalArgumentException("A check answers for LIVENESS, READINESS or STARTUP, not ALL");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
