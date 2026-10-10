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
package io.vidocq.humboldt.it.openliberty;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import java.util.Map;

/**
 * Installs Humboldt as {@code GlobalOpenTelemetry} when the application starts. Under Vidocq the
 * Telemetry extension does this; on another server the application does, once.
 */
@ApplicationScoped
public class TelemetryBootstrap {

    static volatile AutoConfiguredHumboldt humboldt;

    void install(@Observes @Initialized(ApplicationScoped.class) Object started) {
        humboldt = HumboldtAutoConfigure.configure(EnvConfig.of(Map.of(
                "OTEL_SDK_DISABLED", "false",
                "OTEL_SERVICE_NAME", "humboldt-it-openliberty",
                "OTEL_TRACES_EXPORTER", "in-memory",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none"), Map.of()));
        GlobalOpenTelemetry.set(humboldt);
    }
}
