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
module io.opentelemetry.context {
    requires java.logging;

    exports io.opentelemetry.context;
    exports io.opentelemetry.context.propagation;
    // Unqualified on purpose: ComponentLoader is public upstream OpenTelemetry API, used by autoconfigure,
    // autoconfigure-spi (ConfigProperties.getComponentLoader) and the exporters — any consumer may need it.
    exports io.opentelemetry.common;
    // Internal logger used by the API module only (opentelemetry-common is shaded in here since 1.66).
    exports io.opentelemetry.common.impl to io.opentelemetry.api;
    // WeakConcurrentMap, which opentelemetry-exporter-otlp-common (ResourceMarshaler,
    // InstrumentationScopeMarshaler) uses to cache marshalers: without it every OTLP export fails on the module
    // path with an IllegalAccessError (BUG-20261004-03). Qualified to the only OpenTelemetry 1.66 stable artifact
    // that references the package (jdeps over opentelemetry-bom 1.66.0); see humboldt-otel-api's descriptor for
    // the layer rule.
    exports io.opentelemetry.context.internal.shaded to io.opentelemetry.exporter.internal.otlp;
    // Humboldt's own package (src/main/java): extends the qualified exports above to OpenTelemetry modules of a
    // child module layer, which no descriptor export reaches. Called by humboldt-otel-interop only.
    exports io.vidocq.humboldt.otel.context.layer to io.vidocq.humboldt.otel.interop;

    uses io.opentelemetry.context.ContextStorageProvider;
}

