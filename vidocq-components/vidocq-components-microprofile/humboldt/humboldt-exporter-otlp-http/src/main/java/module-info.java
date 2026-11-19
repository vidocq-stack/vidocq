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
 * Humboldt OTLP HTTP Exporter — pushes spans to an OTel Collector
 * via OTLP/HTTP-JSON.
 *
 * <p>M3 minimum viable implementation:</p>
 * <ul>
 *   <li>OTLP/JSON encoding (direct StringBuilder, resourceSpans/scopeSpans/spans/attributes schema)</li>
 *   <li>transport via the JDK {@link java.net.http.HttpClient}</li>
 *   <li>simple retry on 5xx (bounded exponential backoff)</li>
 * </ul>
 *
 * <p>The OTLP/HTTP-protobuf variant and the transport switch to
 * {@code chappe-client} are planned for M3b (see PLAN.md §13).</p>
 */
module io.vidocq.humboldt.exporter.otlp.http {

    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.vidocq.humboldt.sdk.metric;
    requires transitive io.vidocq.humboldt.sdk.log;
    requires java.net.http;
    requires java.logging;

    // For E2E tests that use com.sun.net.httpserver (JDK in-process HttpServer).
    // 'static' = compile-time only; the module remains outside the runtime graph.
    requires static jdk.httpserver;

    exports io.vidocq.humboldt.exporter.otlp.http;
}
