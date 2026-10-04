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
package io.vidocq.runtime.it.jwt;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * JAX-RS test resource for MicroProfile JWT 2.2 security (Cervantes) in Vidocq.
 *
 * <ul>
 *   <li>{@code GET /secured/admin} — {@code @RolesAllowed("admin")}: 401 without token,
 *       403 if token without the group {@code admin}, 200 otherwise. Returns the name + groups of the injected JWT.</li>
 *   <li>{@code GET /secured/public} — {@code @PermitAll}: 200 always, returns the name
 *       of the principal (anonymous if no token).</li>
 * </ul>
 */
@Path("/secured")
@Produces(MediaType.TEXT_PLAIN)
@RequestScoped
public class SecuredResource {

    @Inject
    JsonWebToken jwt;

    @GET
    @Path("/admin")
    @RolesAllowed("admin")
    public String admin() {
        return "admin-ok name=" + jwt.getName() + " groups=" + jwt.getGroups();
    }

    @GET
    @Path("/public")
    @PermitAll
    public String publicEndpoint() {
        return "public-ok name=" + jwt.getName();
    }
}
