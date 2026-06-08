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
package io.vidocq.knock.runtime;

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;

/**
 * Report produced by {@link KnockHealthService} for a given {@link ProbeType}.
 *
 * <p>Encapsulates the result ready to serve to an HTTP transport (Jakarta REST via
 * {@code knock-cassini}, or otherwise): MicroProfile Health 4.0 HTTP code (200 / 503,
 * spec §3) and JSON body compliant with spec §3.1.</p>
 *
 * @param httpStatus MicroProfile Health 4.0 HTTP code (200 if UP, 503 if DOWN)
 * @param json       JSON body for spec §3.1 (never {@code null})
 */
public record HealthReport(int httpStatus, String json) {

    public HealthReport {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
    }
}
