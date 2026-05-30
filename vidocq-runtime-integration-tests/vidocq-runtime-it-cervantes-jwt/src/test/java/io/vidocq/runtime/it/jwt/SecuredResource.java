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
 * JAX-RS test resource for MicroProfile JWT 2.1 security (Cervantes) in Vidocq.
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
