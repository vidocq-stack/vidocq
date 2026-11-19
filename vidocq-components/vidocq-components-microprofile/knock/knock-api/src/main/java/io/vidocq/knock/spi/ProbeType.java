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

/**
 * MicroProfile Health 4.0 probe type.
 *
 * <p>Corresponds to the four endpoints defined by spec §3 :</p>
 * <ul>
 *   <li>{@link #LIVENESS}  → {@code GET /health/live}</li>
 *   <li>{@link #READINESS} → {@code GET /health/ready}</li>
 *   <li>{@link #STARTUP}   → {@code GET /health/started}</li>
 *   <li>{@link #ALL}       → {@code GET /health} (aggregate of all checks)</li>
 * </ul>
 *
 * @see <a href="https://download.eclipse.org/microprofile/microprofile-health-4.0/microprofile-health-spec-4.0.html#_health_endpoints">
 *      MicroProfile Health 4.0 §3</a>
 */
public enum ProbeType {

    /** {@code @Liveness} checks — endpoint {@code /health/live}. */
    LIVENESS,

    /** {@code @Readiness} checks — endpoint {@code /health/ready}. */
    READINESS,

    /** {@code @Startup} checks — endpoint {@code /health/started}. */
    STARTUP,

    /** Aggregate of all checks — endpoint {@code /health}. */
    ALL
}
