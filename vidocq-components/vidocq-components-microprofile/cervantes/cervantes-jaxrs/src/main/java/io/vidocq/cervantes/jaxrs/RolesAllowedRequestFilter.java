package io.vidocq.cervantes.jaxrs;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.Set;

/**
 * Applique l'autorisation d'une méthode de ressource (enregistré par
 * {@link RolesAllowedDynamicFeature}). Priorité {@link Priorities#AUTHORIZATION} : s'exécute après
 * le {@link JwtAuthenticationFilter}, donc {@code getSecurityContext()} reflète déjà le token validé.
 *
 * <ul>
 *   <li>{@code @DenyAll} → toujours {@code 403}.</li>
 *   <li>{@code @RolesAllowed} → {@code 401} si non authentifié, {@code 403} si aucun rôle ne correspond.</li>
 * </ul>
 * ({@code @PermitAll} et les méthodes sans annotation ne font enregistrer aucun filtre.)
 */
@Priority(Priorities.AUTHORIZATION)
public final class RolesAllowedRequestFilter implements ContainerRequestFilter {

    private final Set<String> rolesAllowed;
    private final boolean denyAll;

    private RolesAllowedRequestFilter(Set<String> rolesAllowed, boolean denyAll) {
        this.rolesAllowed = rolesAllowed;
        this.denyAll = denyAll;
    }

    static RolesAllowedRequestFilter denyAll() {
        return new RolesAllowedRequestFilter(Set.of(), true);
    }

    static RolesAllowedRequestFilter rolesAllowed(Set<String> roles) {
        return new RolesAllowedRequestFilter(Set.copyOf(roles), false);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (denyAll) {
            abort(requestContext, Response.Status.FORBIDDEN);
            return;
        }
        SecurityContext security = requestContext.getSecurityContext();
        Principal user = security == null ? null : security.getUserPrincipal();
        if (user == null) {
            abort(requestContext, Response.Status.UNAUTHORIZED); // non authentifié
            return;
        }
        boolean granted = rolesAllowed.stream().anyMatch(security::isUserInRole);
        if (!granted) {
            abort(requestContext, Response.Status.FORBIDDEN); // authentifié mais rôle absent
        }
    }

    private static void abort(ContainerRequestContext requestContext, Response.Status status) {
        requestContext.abortWith(Response.status(status).build());
    }
}
