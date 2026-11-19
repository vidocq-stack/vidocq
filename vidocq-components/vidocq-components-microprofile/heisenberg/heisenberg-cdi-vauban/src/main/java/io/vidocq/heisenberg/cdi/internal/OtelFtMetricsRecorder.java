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
package io.vidocq.heisenberg.cdi.internal;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * MicroProfile Fault Tolerance 4.1 §10 metrics recorder bean (OpenTelemetry). Publishes through
 * {@link OtelFtMetrics} when the OpenTelemetry API is present, and does nothing otherwise: this
 * class refers to no OpenTelemetry type, so an application without Telemetry still starts
 * (BUG-005).
 */
@ApplicationScoped
public class OtelFtMetricsRecorder extends DelegatingFtMetricsRecorder {

    private static final boolean OTEL_PRESENT = apiPresent("io.opentelemetry.api.GlobalOpenTelemetry");

    @PostConstruct
    void init() {
        if (OTEL_PRESENT) {
            delegateTo(new OtelFtMetrics());
        }
    }
}
