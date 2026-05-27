package io.vidocq.cervantes.examples;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.ClaimValue;
import org.eclipse.microprofile.jwt.Claims;

import java.util.Set;

/**
 * Example MicroProfile JWT 2.1 resource secured by Cervantes.
 *
 * <p>Demonstrates the two halves of MP JWT in a JAX-RS resource:</p>
 * <ul>
 *   <li><b>Authorization</b> — {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll} are enforced
 *       by Cervantes' {@code RolesAllowedDynamicFeature} (cervantes-cassini): the verified token's
 *       {@code groups} claim becomes the caller's roles, checked before the method runs.</li>
 *   <li><b>Identity</b> — {@code @Inject @Claim} pulls typed claims of the current request's token
 *       into the (request-scoped) resource, resolved by Cervantes' CDI integration
 *       (cervantes-cdi-vauban).</li>
 * </ul>
 *
 * <p>Wiring (a Chappe deployment): see {@code README.md}. The accompanying test exercises the
 * {@code @Claim} injection under an embedded Vauban container, without an HTTP server.</p>
 */
@Path("/api")
@RequestScoped
public class ProtectedResource {

    /** The {@code upn} (user principal name) standard claim of the current token. */
    @Inject
    @Claim(standard = Claims.upn)
    String upn;

    /** The {@code groups} claim — the caller's roles. */
    @Inject
    @Claim("groups")
    Set<String> groups;

    /** Lazy, request-bound view of the {@code upn} claim (resolves at access time). */
    @Inject
    @Claim("upn")
    ClaimValue<String> upnValue;

    /** Open to anyone, even without a token. */
    @GET
    @Path("/public")
    @PermitAll
    @Produces(MediaType.TEXT_PLAIN)
    public String publicInfo() {
        return "public — no token required";
    }

    /** Any authenticated caller in role {@code user} or {@code admin}. */
    @GET
    @Path("/me")
    @RolesAllowed({"user", "admin"})
    @Produces(MediaType.TEXT_PLAIN)
    public String whoAmI() {
        return "Authenticated as " + upn + " with roles " + groups;
    }

    /** Restricted to callers whose token carries the {@code admin} group. */
    @GET
    @Path("/admin")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_PLAIN)
    public String adminOnly() {
        return "Welcome, administrator " + upnValue.getValue();
    }

    /** Never reachable — demonstrates {@code @DenyAll}. */
    @GET
    @Path("/forbidden")
    @DenyAll
    @Produces(MediaType.TEXT_PLAIN)
    public String forbidden() {
        return "unreachable";
    }
}
