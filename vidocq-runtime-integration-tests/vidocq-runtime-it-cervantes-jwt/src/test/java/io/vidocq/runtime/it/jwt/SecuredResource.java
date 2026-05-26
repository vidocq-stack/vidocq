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
 * Resource JAX-RS de test pour la securite MicroProfile JWT 2.1 (Cervantes) dans Vidocq.
 *
 * <ul>
 *   <li>{@code GET /secured/admin} — {@code @RolesAllowed("admin")} : 401 sans token,
 *       403 si token sans le groupe {@code admin}, 200 sinon. Renvoie le nom + groupes du JWT injecte.</li>
 *   <li>{@code GET /secured/public} — {@code @PermitAll} : 200 toujours, renvoie le nom
 *       du principal (anonyme si pas de token).</li>
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
