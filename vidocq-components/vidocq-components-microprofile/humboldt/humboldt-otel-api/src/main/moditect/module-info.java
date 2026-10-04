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
module io.opentelemetry.api {
    requires transitive io.opentelemetry.context;
    requires java.logging;

    exports io.opentelemetry.api;
    exports io.opentelemetry.api.baggage;
    exports io.opentelemetry.api.baggage.propagation;
    exports io.opentelemetry.api.common;
    exports io.opentelemetry.api.logs;
    exports io.opentelemetry.api.metrics;
    exports io.opentelemetry.api.trace;
    exports io.opentelemetry.api.trace.propagation;

    // Packages that are not public API but that the OpenTelemetry SDK, exporter and sender jars call across jars:
    // api.internal (ConfigUtil, Utils, StringUtils, OtelEncodingUtils...), api.impl (InstrumentationUtil) and
    // api.trace.propagation.internal (W3CTraceContextEncoding). Upstream the API jar is an automatic module that
    // exports every package; without these exports those jars fail on the module path with an IllegalAccessError
    // (BUG-20261004-03). io.opentelemetry.context does the same for context.internal.shaded.
    //
    // Each export is qualified, to exactly the OpenTelemetry 1.66 stable artifacts whose classes reference the
    // package (jdeps -verbose:class over every jar of opentelemetry-bom 1.66.0), named by their
    // Automatic-Module-Name:
    //   opentelemetry-sdk-common                          io.opentelemetry.sdk.common                  internal
    //   opentelemetry-sdk-trace                           io.opentelemetry.sdk.trace                   internal
    //   opentelemetry-sdk-metrics                         io.opentelemetry.sdk.metrics                 internal
    //   opentelemetry-sdk-logs                            io.opentelemetry.sdk.logs                    internal
    //   opentelemetry-sdk-extension-autoconfigure-spi     io.opentelemetry.sdk.autoconfigure.spi       internal
    //   opentelemetry-sdk-extension-jaeger-remote-sampler io.opentelemetry.sdk.extension.trace.jaeger  internal
    //   opentelemetry-extension-trace-propagators         io.opentelemetry.extension.trace.propagation internal
    //   opentelemetry-exporter-common                     io.opentelemetry.exporter.internal           internal, impl
    //   opentelemetry-exporter-otlp-common                io.opentelemetry.exporter.internal.otlp
    //                                                         internal, trace.propagation.internal (+ context)
    //   opentelemetry-exporter-otlp                       io.opentelemetry.exporter.otlp               internal
    //   opentelemetry-exporter-sender-jdk                 io.opentelemetry.exporter.sender.jdk.internal impl
    //   opentelemetry-exporter-sender-okhttp              io.opentelemetry.exporter.sender.okhttp.internal impl
    // OtlpExporterModuleLayerTest (humboldt-otel-interop) exports a span, a metric and a log record through
    // opentelemetry-exporter-otlp and the JDK sender on the module path; the other targets rest on jdeps.
    //
    // A qualified export reaches only target modules of the same module layer or of a parent layer. Incubating
    // (-alpha) artifacts are not listed. Re-check the lists on each OpenTelemetry upgrade; a target module that
    // is absent at run time is ignored. (No comment inside the lists below: ModiTect would take it into the
    // module names.)
    exports io.opentelemetry.api.internal to
            io.opentelemetry.sdk.common,
            io.opentelemetry.sdk.trace,
            io.opentelemetry.sdk.metrics,
            io.opentelemetry.sdk.logs,
            io.opentelemetry.sdk.autoconfigure.spi,
            io.opentelemetry.sdk.extension.trace.jaeger,
            io.opentelemetry.extension.trace.propagation,
            io.opentelemetry.exporter.internal,
            io.opentelemetry.exporter.internal.otlp,
            io.opentelemetry.exporter.otlp;
    exports io.opentelemetry.api.impl to
            io.opentelemetry.exporter.internal,
            io.opentelemetry.exporter.sender.jdk.internal,
            io.opentelemetry.exporter.sender.okhttp.internal;
    exports io.opentelemetry.api.trace.propagation.internal to
            io.opentelemetry.exporter.internal.otlp;

    // Humboldt's own package (src/main/java): extends the qualified exports above to OpenTelemetry modules of a
    // child module layer, which no descriptor export reaches. Called by humboldt-otel-interop only.
    exports io.vidocq.humboldt.otel.api.layer to io.vidocq.humboldt.otel.interop;
}



