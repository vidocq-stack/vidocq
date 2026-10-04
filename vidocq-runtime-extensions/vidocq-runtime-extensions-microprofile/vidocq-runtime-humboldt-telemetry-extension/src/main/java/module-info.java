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
 * Vidocq Runtime Telemetry extension — Humboldt branch (MicroProfile Telemetry 2.2)
 * on the Vidocq life cycle.
 *
 * <p>Auto-config via env vars {@code OTEL_*} and system properties {@code otel.*},
 * installation of {@code GlobalOpenTelemetry}, shutdown ordered at end of life.</p>
 *
 * <p>Discovery via ServiceLoader (META-INF/services + {@code provides} Java Modules).</p>
 */
module io.vidocq.runtime.extensions.microprofile.humboldt {

    requires io.vidocq.runtime.spi;
    requires io.vidocq.humboldt.runtime;
    requires io.vidocq.humboldt.sdk.common;
    requires io.vidocq.humboldt.api;
    requires io.vidocq.humboldt.otel.interop;
    requires io.vidocq.vauban.core;
    requires io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires jakarta.cdi;
    requires java.logging;

    // The interop module only `requires static` the OTel SDK automatic modules
    // (the consumer supplies them). This extension IS that consumer: require them
    // here so the module graph resolves them on module-path boots.
    requires io.opentelemetry.sdk.common;
    requires io.opentelemetry.sdk.trace;
    requires io.opentelemetry.sdk.metrics;
    requires io.opentelemetry.sdk.autoconfigure.spi;
    requires io.opentelemetry.sdk.testing;
    requires io.opentelemetry.extension.trace.propagation;

    // Export package to enable @Inject AutoConfiguredHumboldt from apps/tests
    // (HumboldtHolder must be accessible to the CDI Vauban container).
    exports io.vidocq.runtime.extensions.microprofile.humboldt;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.microprofile.humboldt.HumboldtExtension;
}
