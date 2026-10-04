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

    // OpenTelemetry's cross-jar implementation package (ConfigUtil, Utils, StringUtils, OtelEncodingUtils...).
    // Upstream the API jar is an automatic module that exports every package, and the SDK and exporter jars
    // call into it: without this export they fail on the module path with an IllegalAccessError
    // (BUG-20261004-03). Qualified, never public API: exactly the OpenTelemetry 1.66 stable artifacts that
    // reference it (jdeps on every jar of opentelemetry-bom 1.66.0), named by their Automatic-Module-Name:
    //   opentelemetry-sdk-common                          -> io.opentelemetry.sdk.common
    //   opentelemetry-sdk-trace                           -> io.opentelemetry.sdk.trace
    //   opentelemetry-sdk-metrics                         -> io.opentelemetry.sdk.metrics
    //   opentelemetry-sdk-logs                            -> io.opentelemetry.sdk.logs
    //   opentelemetry-sdk-extension-autoconfigure-spi     -> io.opentelemetry.sdk.autoconfigure.spi
    //   opentelemetry-sdk-extension-jaeger-remote-sampler -> io.opentelemetry.sdk.extension.trace.jaeger
    //   opentelemetry-extension-trace-propagators         -> io.opentelemetry.extension.trace.propagation
    //   opentelemetry-exporter-common                     -> io.opentelemetry.exporter.internal
    //   opentelemetry-exporter-otlp-common                -> io.opentelemetry.exporter.internal.otlp
    //   opentelemetry-exporter-otlp                       -> io.opentelemetry.exporter.otlp
    // Incubating (-alpha) artifacts are not listed; they need --add-exports. Re-check on each OpenTelemetry
    // upgrade: a target module that is absent at run time is ignored. (No comment inside the list below:
    // ModiTect would take it into the module names.)
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
}



