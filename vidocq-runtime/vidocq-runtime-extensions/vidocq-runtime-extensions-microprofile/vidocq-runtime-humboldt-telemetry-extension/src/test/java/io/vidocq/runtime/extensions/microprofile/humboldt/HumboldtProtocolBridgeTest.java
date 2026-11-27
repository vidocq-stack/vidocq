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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The OTLP protocol keys reach Humboldt from the Vidocq configuration ({@code vidocq.properties}, where
 * {@code vidocq extension add telemetry-humboldt} writes {@code otel.exporter.otlp.protocol}), like the endpoint
 * (Vidocq/vidocq#830).
 */
class HumboldtProtocolBridgeTest {

    @Test
    void the_general_and_per_signal_protocols_are_bridged() {
        Map<String, String> vidocqProperties = Map.of(
                "otel.exporter.otlp.protocol", "http/protobuf",
                "otel.exporter.otlp.traces.protocol", "http/json",
                "otel.exporter.otlp.metrics.protocol", "http/json",
                "OTEL_EXPORTER_OTLP_LOGS_PROTOCOL", "http/json");

        Map<String, String> env = HumboldtExtension.bridgeFromVidocqConfig(
                key -> Optional.ofNullable(vidocqProperties.get(key)));

        assertEquals("http/protobuf", env.get("OTEL_EXPORTER_OTLP_PROTOCOL"));
        assertEquals("http/json", env.get("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"));
        assertEquals("http/json", env.get("OTEL_EXPORTER_OTLP_METRICS_PROTOCOL"));
        assertEquals("http/json", env.get("OTEL_EXPORTER_OTLP_LOGS_PROTOCOL"));
    }
}
