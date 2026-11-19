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
/**
 * Humboldt SDK Trace — OpenTelemetry implementation of the {@code traces} signal.
 *
 * <ul>
 *   <li>{@link io.vidocq.humboldt.sdk.trace.SdkTracerProvider} — immutable {@code Tracer} factory</li>
 *   <li>Samplers: always_on, always_off, parentbased, traceidratio</li>
 *   <li>Processors: {@code SimpleSpanProcessor} (synchronous), {@code BatchSpanProcessor} (virtual-thread)</li>
 *   <li>Utility exporters: {@code InMemorySpanExporter}, {@code LoggingSpanExporter}</li>
 * </ul>
 *
 * <p>OTLP serialization (HTTP/protobuf) is provided by the separate
 * {@code humboldt-exporter-otlp-http} module in M3.</p>
 */
module io.vidocq.humboldt.sdk.trace {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.common;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires java.logging;

    exports io.vidocq.humboldt.sdk.trace;
    exports io.vidocq.humboldt.sdk.trace.data;
    exports io.vidocq.humboldt.sdk.trace.export;
    exports io.vidocq.humboldt.sdk.trace.samplers;
}
