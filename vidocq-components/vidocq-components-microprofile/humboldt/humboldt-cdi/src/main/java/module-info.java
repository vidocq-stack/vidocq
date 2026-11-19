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
 * Humboldt CDI — automatic interception of
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan @WithSpan}
 * (standard OpenTelemetry public API annotation, aligned with the MicroProfile
 * Telemetry 2.2 TCK).
 *
 * <p>The user only writes {@code @WithSpan}. The
 * {@link io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension} (CDI 4.x
 * BuildCompatibleExtension) automatically adds the internal marker
 * {@link io.vidocq.humboldt.cdi.SpanBinding} at build time, which activates
 * {@link io.vidocq.humboldt.cdi.WithSpanInterceptor}.</p>
 *
 * <p>Compatible with CDI 4.1 Lite (Vauban) and CDI 4.1 Full (Weld) — the
 * BuildCompatibleExtension is the standard CDI 4.x mechanism shared between
 * Lite and Full.</p>
 */
module io.vidocq.humboldt.cdi {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    // OpenTelemetry instrumentation-annotations: automatic module
    // (Automatic-Module-Name with underscore, not dot).
    requires transitive io.opentelemetry.instrumentation_annotations;
    requires transitive jakarta.cdi;
    requires transitive jakarta.interceptor;
    requires java.logging;
    // Required at runtime under any CDI container, not only Vauban: the build weaves a
    // `(io.vidocq.vauban.api.ProxyLink)` entry constructor into the normal-scoped beans, so their
    // classes cannot be loaded without this module. It also supplies the VaubanComponentProvider
    // service type.
    requires io.vidocq.vauban.api;

    exports io.vidocq.humboldt.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension;

    // In-module instantiation and producer invocation of this package's beans (the @Produces in
    // HumboldtTelemetryProducers and the WithSpanInterceptor), generated as _VaubanComponents
    // co-located in io.vidocq.humboldt.cdi — so the container needs no `opens … to
    // io.vidocq.vauban.core`. APT-generated, inert under Weld.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.humboldt.cdi._VaubanComponents;
}
