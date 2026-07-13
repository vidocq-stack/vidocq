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
 * Humboldt OTel SDK Interop — bridges OpenTelemetry SDK autoconfigure SPI
 * providers (span/metric exporters, samplers, propagators, resources,
 * customizers) discovered via {@code ServiceLoader} to the Humboldt SDK.
 *
 * <p>Entry point: {@link io.vidocq.humboldt.otel.interop.OtelSpiAutoConfiguration}.
 * The OTel SDK modules are {@code requires static}: the consumer supplies them
 * at runtime (Humboldt never bundles the OTel SDK — this module only adapts it
 * when a deployment brings it, e.g. the MicroProfile Telemetry TCK).</p>
 */
module io.vidocq.humboldt.otel.interop {

    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.vidocq.humboldt.sdk.metric;
    requires io.vidocq.humboldt.sdk.common;
    requires io.vidocq.humboldt.propagator.w3c;
    requires transitive io.opentelemetry.api;
    requires transitive io.opentelemetry.context;

    // OTel SDK automatic modules — provided by the consumer at runtime.
    requires static io.opentelemetry.sdk.common;
    requires static io.opentelemetry.sdk.trace;
    requires static io.opentelemetry.sdk.metrics;
    requires static io.opentelemetry.sdk.autoconfigure.spi;
    requires static io.opentelemetry.sdk.testing;
    requires static io.opentelemetry.extension.trace.propagation;

    uses io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
    uses io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider;
    uses io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
    uses io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;
    uses io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
    uses io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

    exports io.vidocq.humboldt.otel.interop;
}
