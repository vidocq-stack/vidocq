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
    // OpenApiResource and GrimmModelCache. A named module: an automatic one let cassini-maven-plugin
    // split its package into the application module (Vidocq/grimm#15).
    requires io.vidocq.grimm.cdi.vauban;
    // The application annotates its resources with the MicroProfile OpenAPI annotations.
    requires transitive org.eclipse.microprofile.openapi;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.microprofile.grimm.openapi.GrimmOpenApiExtension;
}
