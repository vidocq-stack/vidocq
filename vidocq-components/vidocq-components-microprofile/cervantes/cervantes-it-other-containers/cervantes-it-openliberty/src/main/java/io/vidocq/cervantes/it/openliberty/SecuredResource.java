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
package io.vidocq.cervantes.it.openliberty;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.TreeSet;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.ClaimValue;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Resources guarded by Cervantes: the role check and the caller's claims come from the bearer token. */
@Path("/secured")
@RequestScoped
@Produces(MediaType.TEXT_PLAIN)
public class SecuredResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    @Claim(standard = Claims.upn)
    ClaimValue<String> upn;

    @GET
    @Path("/public")
    @PermitAll
    public String everyone() {
        return "public";
    }

    @GET
    @Path("/admin")
    @RolesAllowed("admin")
    public String admin() {
        return "admin " + upn.getValue() + " " + new TreeSet<>(jwt.getGroups());
    }

    @GET
    @Path("/auditor")
    @RolesAllowed("auditor")
    public String auditor() {
        return "auditor " + jwt.getName();
    }
}
