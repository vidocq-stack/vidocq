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
package io.vidocq.runtime.examples.jwt;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

/**
 * JWT-secured JAX-RS resource. Exercises, on the strict module path:
 * <ul>
 *   <li>the cervantes JWT auth filter (validates the Bearer token, installs a
 *       {@code SecurityContext}) — cervantes-jaxrs;</li>
 *   <li>the {@code @RolesAllowed} DynamicFeature — cervantes-jaxrs;</li>
 *   <li>the cervantes BuildCompatibleExtension — loaded via ServiceLoader.</li>
 * </ul>
 * Uses the standard JAX-RS {@code SecurityContext} (no {@code @Inject JsonWebToken}) so the
 * example needs no cross-module CDI producer at APT time.
 */
@Path("/secured")
@Produces(MediaType.TEXT_PLAIN)
@RequestScoped
public class SecuredResource {

    @GET
    @Path("/admin")
    @RolesAllowed("admin")
    public String admin(@Context SecurityContext sec) {
        var p = sec.getUserPrincipal();
        return "admin-ok user=" + (p == null ? "?" : p.getName());
    }

    @GET
    @Path("/public")
    @PermitAll
    public String publicEndpoint(@Context SecurityContext sec) {
        var p = sec.getUserPrincipal();
        return "public-ok user=" + (p == null ? "anonymous" : p.getName());
    }
}
