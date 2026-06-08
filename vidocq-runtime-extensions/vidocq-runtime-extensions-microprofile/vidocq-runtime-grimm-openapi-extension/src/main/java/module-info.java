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
 * Serves the Grimm-assembled OpenAPI document at {@code /openapi} on the Chappe engine. See
 * {@link io.vidocq.runtime.extensions.microprofile.grimm.openapi.GrimmOpenApiExtension}.
 */
module io.vidocq.runtime.extensions.microprofile.grimm.openapi {
    requires io.vidocq.runtime.spi;
    requires io.vidocq.runtime.extensions.essentials.chappe;
    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;
    // Grimm currently ships as an AUTOMATIC module (its module-info sources are not compiled into the
    // jar), hence the filename-derived name grimm.cdi.vauban (which holds both OpenApiResource and
    // GrimmModelCache). Switch to io.vidocq.grimm.cdi.vauban once Grimm ships an explicit module.
    requires grimm.cdi.vauban;
    // grimm.cdi.vauban is an AUTOMATIC module: it uses the MicroProfile OpenAPI API but does not
    // declare requires, so on the module path the API module would not be pulled into the resolved
    // graph (NoClassDefFoundError: org.eclipse.microprofile.openapi.models.OpenAPI). Require it
    // transitively here so every consumer (e.g. an app module) resolves it.
    requires transitive org.eclipse.microprofile.openapi;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.microprofile.grimm.openapi.GrimmOpenApiExtension;
}
